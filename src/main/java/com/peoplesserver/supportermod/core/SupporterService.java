package com.peoplesserver.supportermod.core;

import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.platform.Messenger;
import com.peoplesserver.supportermod.platform.PlayerDirectory;
import com.peoplesserver.supportermod.platform.PluginLog;
import com.peoplesserver.supportermod.storage.SupporterStorage;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single source of truth for supporter entitlement.
 *
 * <p>Nothing outside this class may read or write the supporter table. Perk systems ask
 * {@link #isSupporter(UUID)} and nothing else.
 *
 * <p>Two rules are load-bearing and are covered by tests:
 * <ul>
 *   <li>A renewal extends from the existing expiry, never from now, so buying early can never
 *       cost somebody time.
 *   <li>A grant carrying a transaction id is applied at most once, because the payment
 *       provider retries delivery until the server acknowledges it.
 * </ul>
 */
public final class SupporterService {

    private static final long DAY_MS = 86_400_000L;

    /** Whether the player should be prompted to renew on login. */
    public enum Nudge {
        NONE,
        /** Inside {@code renewalNudgeDaysBefore} of expiry. */
        RENEWAL_SOON,
        /** Expired but still inside the grace window. */
        GRACE
    }

    /** What happened when a player joined. */
    public record LoginResult(int claimedDays, Nudge nudge, SupporterStatus status) {}

    /** Wraps SQL failures so callers keep the clean API the spec asks for. */
    public static final class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final SupporterStorage storage;
    private final SupporterConfig config;
    private final Clock clock;
    private final PlayerDirectory directory;
    private final Messenger messenger;
    private final PluginLog log;

    /**
     * Active supporters, keyed by UUID.
     *
     * <p>Present here means "flagged active", not "currently entitled" — a record can sit in
     * the cache with a closed grace window between the moment it lapses and the nightly
     * reconcile. Every read therefore re-checks the timestamp rather than trusting presence.
     */
    private final Map<UUID, SupporterRecord> cache = new ConcurrentHashMap<>();

    public SupporterService(
            SupporterStorage storage,
            SupporterConfig config,
            Clock clock,
            PlayerDirectory directory,
            Messenger messenger,
            PluginLog log) {
        this.storage = storage;
        this.config = config;
        this.clock = clock;
        this.directory = directory;
        this.messenger = messenger;
        this.log = log;
        reloadCache();
    }

    /** Reloads the entitlement cache from the database. */
    public void reloadCache() {
        try {
            Map<UUID, SupporterRecord> fresh = new ConcurrentHashMap<>();
            for (SupporterRecord r : storage.findActive()) {
                fresh.put(r.uuid(), r);
            }
            cache.keySet().retainAll(fresh.keySet());
            cache.putAll(fresh);
            log.info("Entitlement cache loaded: " + cache.size() + " active supporter(s)");
        } catch (SQLException e) {
            throw new StorageException("Failed to load supporter cache", e);
        }
    }

    // --- reads ----------------------------------------------------------------------------

    /**
     * Whether the player currently gets supporter perks. True during the grace period.
     *
     * <p>O(1) map lookup with no database access — this is called from the chat event and,
     * in Phase 4, from a repeating particle task.
     */
    public boolean isSupporter(UUID uuid) {
        SupporterRecord r = cache.get(uuid);
        return r != null && r.entitledAt(clock.millis());
    }

    /** Full record, including lapsed players who are no longer cached. */
    public Optional<SupporterRecord> get(UUID uuid) {
        SupporterRecord cached = cache.get(uuid);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            return storage.find(uuid);
        } catch (SQLException e) {
            throw new StorageException("Failed to read supporter " + uuid, e);
        }
    }

    public SupporterStatus status(UUID uuid) {
        return get(uuid).map(r -> r.statusAt(clock.millis())).orElse(SupporterStatus.NONE);
    }

    /** Whole days of paid time left. Zero once expired, even inside the grace window. */
    public long daysRemaining(UUID uuid) {
        return get(uuid).map(r -> r.daysRemainingAt(clock.millis())).orElse(0L);
    }

    /** Active supporters ordered by tenure, for {@code /supporters}. */
    public List<SupporterRecord> activeByTenure() {
        try {
            return storage.findActiveByTenure();
        } catch (SQLException e) {
            throw new StorageException("Failed to list supporters", e);
        }
    }

    /** Snapshot of currently entitled players, for perk systems that iterate. */
    public List<UUID> entitledPlayers() {
        long now = clock.millis();
        List<UUID> out = new ArrayList<>();
        for (SupporterRecord r : cache.values()) {
            if (r.entitledAt(now)) {
                out.add(r.uuid());
            }
        }
        return Collections.unmodifiableList(out);
    }

    // --- writes ---------------------------------------------------------------------------

    /**
     * Grants or extends supporter time.
     *
     * @param txn payment-provider transaction id, or null for a manual admin grant. When
     *     present it is an idempotency key: a second call with the same id is ignored.
     */
    public synchronized GrantResult grant(
            UUID uuid, String username, int days, String source, String txn) {
        if (days <= 0) {
            throw new IllegalArgumentException("days must be positive, got " + days);
        }
        Objects.requireNonNull(uuid, "uuid");
        try {
            return storage.transact(() -> applyGrant(uuid, username, days, source, txn));
        } catch (SQLException e) {
            throw new StorageException("Failed to grant supporter to " + uuid, e);
        }
    }

    /**
     * Grants by username, for a payment provider that only knows the player's name.
     *
     * <p>If the username cannot be resolved to a UUID — a gift for somebody who has never
     * logged in — the grant is queued and applied on their next login. The purchase is never
     * dropped.
     */
    public synchronized GrantResult grantByUsername(
            String username, int days, String source, String txn) {
        if (days <= 0) {
            throw new IllegalArgumentException("days must be positive, got " + days);
        }
        Objects.requireNonNull(username, "username");
        try {
            return storage.transact(() -> {
                if (isDuplicate(txn)) {
                    return duplicate(null);
                }
                Optional<UUID> resolved = directory.uuidFor(username);
                if (resolved.isEmpty()) {
                    // Fall back to a record we already hold — a lapsed supporter renewing
                    // while offline still resolves without the server's help.
                    resolved = storage.findByUsername(username).map(SupporterRecord::uuid);
                }
                if (resolved.isPresent()) {
                    return applyGrant(resolved.get(), username, days, source, txn);
                }
                long now = clock.millis();
                storage.addPendingGrant(username, days, source, txn, now);
                storage.log(null, username, "GRANT_QUEUED",
                        days + "d pending login", source, now);
                log.info("Queued " + days + "d for unresolved username " + username);
                return new GrantResult(GrantResult.Outcome.QUEUED_PENDING, null, 0);
            });
        } catch (SQLException e) {
            throw new StorageException("Failed to grant supporter to " + username, e);
        }
    }

    /** Ends entitlement immediately. Tenure and paid-for unlocks are never deleted. */
    public synchronized void revoke(UUID uuid, String reason) {
        revoke(uuid, reason, "admin");
    }

    public synchronized void revoke(UUID uuid, String reason, String actor) {
        try {
            storage.transact(() -> {
                Optional<SupporterRecord> existing = storage.find(uuid);
                if (existing.isEmpty()) {
                    return null;
                }
                long now = clock.millis();
                SupporterRecord updated = existing.get().deactivatedAt(now);
                storage.upsert(updated);
                cache.remove(uuid);
                storage.log(uuid, updated.username(), "REVOKE", reason, actor, now);
                log.info("Revoked supporter for " + uuid + ": " + reason);
                return null;
            });
        } catch (SQLException e) {
            throw new StorageException("Failed to revoke supporter " + uuid, e);
        }
    }

    /**
     * Expires everyone past their grace window.
     *
     * <p>Runs nightly and once at startup — startup matters because the server may have been
     * offline across the scheduled hour.
     */
    public synchronized ReconcileReport reconcile() {
        long now = clock.millis();
        try {
            List<UUID> expired = storage.transact(() -> {
                List<UUID> ids = new ArrayList<>();
                for (SupporterRecord r : storage.findLapsed(now)) {
                    storage.upsert(r.deactivatedAt(now));
                    cache.remove(r.uuid());
                    storage.log(r.uuid(), r.username(), "EXPIRE",
                            "grace window closed", "system", now);
                    ids.add(r.uuid());
                }
                return ids;
            });
            for (UUID uuid : expired) {
                if (directory.isOnline(uuid)) {
                    messenger.send(uuid, "Your supporter rank has expired. "
                            + "Thank you for supporting the server — /supporter to renew.");
                }
            }
            if (!expired.isEmpty()) {
                log.info("Reconcile expired " + expired.size() + " supporter(s)");
            }
            return new ReconcileReport(now, List.copyOf(expired));
        } catch (SQLException e) {
            throw new StorageException("Reconcile failed", e);
        }
    }

    /**
     * Claims any queued grants and decides whether to nudge about renewal.
     *
     * <p>Call from the player-join event once Phase 0 confirms which event that is.
     */
    public synchronized LoginResult onLogin(UUID uuid, String username) {
        long now = clock.millis();
        int claimedDays = 0;
        try {
            claimedDays = storage.transact(() -> {
                int days = 0;
                for (SupporterStorage.PendingGrant pending : storage.unclaimedGrants(username)) {
                    GrantResult result = applyGrant(
                            uuid, username, pending.days(), pending.source(), pending.txn());
                    storage.markClaimed(pending.id(), clock.millis());
                    if (result.applied()) {
                        days += pending.days();
                    }
                }
                // Keep the stored username current so admin lookups by name keep working
                // after a rename.
                Optional<SupporterRecord> current = storage.find(uuid);
                if (current.isPresent() && !equalsIgnoreCase(current.get().username(), username)) {
                    SupporterRecord renamed = current.get().withUsername(username);
                    storage.upsert(renamed);
                    if (renamed.active()) {
                        cache.put(uuid, renamed);
                    }
                }
                return days;
            });
        } catch (SQLException e) {
            throw new StorageException("Login handling failed for " + uuid, e);
        }

        if (claimedDays > 0) {
            messenger.send(uuid, "Your supporter rank is active — "
                    + claimedDays + " day(s) added. Thank you!");
        }

        SupporterStatus status = status(uuid);
        Nudge nudge = nudgeFor(uuid, status, now);
        switch (nudge) {
            case GRACE -> messenger.send(uuid, "Your supporter rank has lapsed. "
                    + "Perks stay on for a short grace period — /supporter to renew.");
            case RENEWAL_SOON -> messenger.send(uuid, "Your supporter rank expires in "
                    + daysRemaining(uuid) + " day(s) — /supporter to renew.");
            case NONE -> { }
        }
        return new LoginResult(claimedDays, nudge, status);
    }

    // --- internals ------------------------------------------------------------------------

    /** Must be called inside a transaction. */
    private GrantResult applyGrant(
            UUID uuid, String username, int days, String source, String txn) throws SQLException {
        if (isDuplicate(txn)) {
            return duplicate(storage.find(uuid).orElse(null));
        }
        long now = clock.millis();
        Optional<SupporterRecord> existing = storage.find(uuid);

        // Extend from the later of now and the current expiry. Renewing early must never
        // shorten somebody's time; renewing after a lapse must not back-date the new period.
        long base = existing.map(r -> Math.max(now, r.expiresAt())).orElse(now);
        long expiresAt = base + days * DAY_MS;
        long graceUntil = expiresAt + config.graceDays() * DAY_MS;

        SupporterRecord updated = new SupporterRecord(
                uuid,
                username != null ? username : existing.map(SupporterRecord::username).orElse(null),
                true,
                existing.map(SupporterRecord::firstGrantedAt).orElse(now),
                now,
                expiresAt,
                graceUntil,
                existing.map(SupporterRecord::totalDays).orElse(0) + days,
                source);

        storage.upsert(updated);
        cache.put(uuid, updated);

        if (txn != null && !txn.isBlank()) {
            storage.recordTxn(txn, uuid, days, source, now);
        }
        GrantResult.Outcome outcome =
                existing.isPresent() ? GrantResult.Outcome.EXTENDED : GrantResult.Outcome.CREATED;
        storage.log(uuid, updated.username(), outcome == GrantResult.Outcome.CREATED
                        ? "GRANT" : "RENEW",
                days + "d, expires " + expiresAt + (txn == null ? "" : ", txn " + txn),
                source, now);
        log.info("Granted " + days + "d to " + updated.username() + " (" + outcome + ")");
        return new GrantResult(outcome, updated, days);
    }

    private boolean isDuplicate(String txn) throws SQLException {
        if (txn == null || txn.isBlank()) {
            return false;
        }
        if (storage.txnExists(txn)) {
            log.warn("Ignoring duplicate delivery of transaction " + txn);
            return true;
        }
        return false;
    }

    private static GrantResult duplicate(SupporterRecord record) {
        return new GrantResult(GrantResult.Outcome.DUPLICATE_IGNORED, record, 0);
    }

    private Nudge nudgeFor(UUID uuid, SupporterStatus status, long now) {
        if (status == SupporterStatus.GRACE) {
            return Nudge.GRACE;
        }
        if (status == SupporterStatus.ACTIVE
                && daysRemaining(uuid) <= config.renewalNudgeDaysBefore()) {
            return Nudge.RENEWAL_SOON;
        }
        return Nudge.NONE;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }
}
