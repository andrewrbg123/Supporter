package com.peoplesserver.supportermod.core;

import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.platform.Messenger;
import com.peoplesserver.supportermod.platform.PermissionSync;
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
    private final PermissionSync permissions;

    /**
     * Active supporters, keyed by UUID.
     *
     * <p>Present here means "flagged active", not "currently entitled" — a record can sit in
     * the cache with a closed grace window between the moment it lapses and the nightly
     * reconcile. Every read therefore re-checks the timestamp rather than trusting presence.
     */
    private final Map<UUID, SupporterRecord> cache = new ConcurrentHashMap<>();

    /**
     * Chat identity, filled lazily and invalidated on write.
     *
     * <p>Separate from {@link #cache} on purpose. Entitlement is time-sensitive and re-checked
     * on every read; identity is not — a title is a title whether or not the rank is current.
     */
    private final Map<UUID, SupporterIdentity> identityCache = new ConcurrentHashMap<>();

    /** Without permission syncing — the shape tests use, and a server with no LuckPerms. */
    public SupporterService(
            SupporterStorage storage,
            SupporterConfig config,
            Clock clock,
            PlayerDirectory directory,
            Messenger messenger,
            PluginLog log) {
        this(storage, config, clock, directory, messenger, log, PermissionSync.noop());
    }

    public SupporterService(
            SupporterStorage storage,
            SupporterConfig config,
            Clock clock,
            PlayerDirectory directory,
            Messenger messenger,
            PluginLog log,
            PermissionSync permissions) {
        this.storage = storage;
        this.config = config;
        this.clock = clock;
        this.directory = directory;
        this.messenger = messenger;
        this.log = log;
        this.permissions = permissions == null ? PermissionSync.noop() : permissions;
        reloadCache();
    }

    /**
     * Announces a brand-new supporter to the server, once.
     *
     * <p>Only on {@link GrantResult.Outcome#CREATED} — never on a renewal, and never on a
     * duplicate delivery. Somebody who renews every month does not want the server told about
     * it twelve times a year, and the payment provider retrying a command must not produce a
     * second announcement.
     */
    private void announceIfNew(GrantResult result, String username) {
        if (!config.announceNewSupporters()
                || result == null
                || result.outcome() != GrantResult.Outcome.CREATED) {
            return;
        }
        String name = username != null ? username
                : (result.record() != null ? result.record().username() : null);
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            messenger.broadcast(name + " just became a supporter — thank you!");
        } catch (RuntimeException e) {
            log.error("Failed to announce new supporter " + name, e);
        }
    }

    /**
     * Pushes a player's current entitlement out to the permissions plugin.
     *
     * <p>Called <b>after</b> the database transaction commits, never inside it: the permissions
     * API is asynchronous and a slow or failing external call must not hold a SQLite write open,
     * nor roll back a purchase that already succeeded.
     */
    private void syncPermissions(UUID uuid) {
        if (uuid == null) {
            return;
        }
        try {
            Optional<SupporterRecord> record = get(uuid);
            if (record.isPresent() && record.get().entitledAt(clock.millis())) {
                permissions.grant(uuid, record.get().username(), record.get().graceUntil());
            } else {
                permissions.revoke(uuid, record.map(SupporterRecord::username).orElse(null));
            }
        } catch (RuntimeException e) {
            // Never fail the operation that triggered this. The money has already moved.
            log.error("Permission sync failed for " + uuid, e);
        }
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

    /**
     * Looks a supporter up by username, for admin commands — which know names, not UUIDs.
     *
     * <p>Reads the stored record rather than the online directory, so an offline supporter
     * still resolves. Phase 0b confirmed the server has no offline username index, so this is
     * the only way {@code /supporter revoke Someone} can work while they are logged out.
     *
     * <p>Someone who has never been a supporter returns empty even while connected, which is
     * the right answer for revoke: there is nothing to revoke.
     */
    public Optional<SupporterRecord> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        try {
            return storage.findByUsername(username.trim());
        } catch (SQLException e) {
            throw new StorageException("Failed to read supporter " + username, e);
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

    // --- identity (Phase 2) -----------------------------------------------------------------

    /**
     * A player's chat identity, cached.
     *
     * <p>Cached because the chat formatter calls this for every message a supporter sends. A
     * database read per chat line would put SQLite on the hot path, which is precisely what the
     * entitlement cache exists to avoid. Filled lazily on the player's first message, so a
     * quiet session costs nothing, and invalidated on every write.
     *
     * <p>Deliberately NOT gated on entitlement: a lapsed supporter's title is still returned
     * here. Whether it renders is the chat layer's decision, and keeping the two separate means
     * renewing restores what they had rather than silently discarding it.
     */
    public SupporterIdentity identity(UUID uuid) {
        if (uuid == null) {
            return SupporterIdentity.NONE;
        }
        SupporterIdentity cached = identityCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        try {
            SupporterIdentity loaded = storage.findIdentity(uuid);
            identityCache.put(uuid, loaded);
            return loaded;
        } catch (SQLException e) {
            // Chat must not break because a title could not be read.
            log.error("Failed to read identity for " + uuid, e);
            return SupporterIdentity.NONE;
        }
    }

    /**
     * Sets or clears a custom title. Null or blank clears it.
     *
     * @throws IllegalArgumentException with a player-readable reason if the title is rejected
     */
    public synchronized SupporterIdentity setTitle(UUID uuid, String title) {
        String clean = validateTitle(title);
        return writeIdentity(uuid, identity(uuid).withTitle(clean), "TITLE",
                clean == null ? "cleared" : clean);
    }

    /**
     * Sets or clears the chat colour. Null or blank clears it.
     *
     * @throws IllegalArgumentException with a player-readable reason if the colour is not on
     *     the allowed list
     */
    public synchronized SupporterIdentity setChatColor(UUID uuid, String color) {
        String clean = validateColor(color);
        return writeIdentity(uuid, identity(uuid).withColor(clean), "COLOUR",
                clean == null ? "cleared" : clean);
    }

    /**
     * Sets or clears the particle trail. Null or blank clears it.
     *
     * @throws IllegalArgumentException with a player-readable reason if the id is not configured
     */
    public synchronized SupporterIdentity setTrail(UUID uuid, String trailId) {
        String clean = validateTrail(trailId);
        return writeIdentity(uuid, identity(uuid).withTrail(clean), "TRAIL",
                clean == null ? "cleared" : clean);
    }

    /**
     * Sets whether this player wants other people's trails hidden.
     *
     * <p>Not gated on entitlement — anybody may turn other players' particles off. A cosmetic
     * perk that cannot be switched off by the people who have to look at it is a nuisance, not
     * a perk.
     */
    /**
     * Sets or clears the body-tint skin. Null or blank clears it.
     *
     * <p>No catalogue validation here, deliberately: the catalogue lives in the UI layer
     * (SkinChanger.SKINS), the command validates against it before calling, and a stored name
     * that later leaves the catalogue is ignored at login rather than treated as an error.
     */
    public synchronized SupporterIdentity setSkin(UUID uuid, String skin) {
        String clean = skin == null || skin.isBlank() ? null : skin.trim().toLowerCase();
        return writeIdentity(uuid, identity(uuid).withSkin(clean), "SKIN",
                clean == null ? "cleared" : clean);
    }

    public synchronized SupporterIdentity setHideTrails(UUID uuid, boolean hide) {
        return writeIdentity(uuid, identity(uuid).withHideTrails(hide), "TRAIL_VISIBILITY",
                hide ? "hidden" : "shown");
    }

    /** @return the trail id in its configured spelling, or null to clear */
    private String validateTrail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String wanted = raw.trim();
        for (String id : config.trails().keySet()) {
            if (id.equalsIgnoreCase(wanted)) {
                return id;
            }
        }
        throw new IllegalArgumentException("No such trail. Available: "
                + String.join(", ", config.trails().keySet()));
    }

    /**
     * Same as {@link #setTrail} but refuses a trail the player has not unlocked.
     *
     * <p>Ownership is checked here rather than only in the command, so a future UI cannot
     * accidentally bypass it.
     */
    public synchronized SupporterIdentity selectTrail(UUID uuid, String trailId) {
        String clean = validateTrail(trailId);
        if (clean != null && !owns(uuid, clean)) {
            throw new IllegalArgumentException("You have not unlocked " + clean
                    + " — it costs " + config.trailCost(clean)
                    + " tokens. /supporter shop to see what you can afford.");
        }
        return setTrail(uuid, clean);
    }

    private SupporterIdentity writeIdentity(
            UUID uuid, SupporterIdentity updated, String action, String detail) {
        Objects.requireNonNull(uuid, "uuid");
        long now = clock.millis();
        try {
            storage.transact(() -> {
                storage.saveIdentity(uuid, updated, now);
                storage.log(uuid, directory.usernameFor(uuid).orElse(null),
                        action, detail, "player", now);
                return null;
            });
        } catch (SQLException e) {
            throw new StorageException("Failed to save identity for " + uuid, e);
        }
        identityCache.put(uuid, updated);
        return updated;
    }

    /** @return the cleaned title, or null to clear */
    private String validateTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String clean = raw.trim();

        // Control characters would let a title break the chat line it sits in.
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException("Titles cannot contain control characters.");
            }
        }
        if (clean.length() > config.maxTitleLength()) {
            throw new IllegalArgumentException("Titles are limited to "
                    + config.maxTitleLength() + " characters — yours is " + clean.length() + ".");
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        for (String banned : config.titleBlocklist()) {
            if (banned != null && !banned.isBlank()
                    && lower.contains(banned.toLowerCase(Locale.ROOT))) {
                // Deliberately does not echo which word matched: that turns the blocklist into
                // an oracle somebody can probe for the full list.
                throw new IllegalArgumentException("That title is not allowed.");
            }
        }
        return clean;
    }

    /** @return the colour in its configured casing, or null to clear */
    private String validateColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String wanted = raw.trim();
        for (String allowed : config.allowedChatColors()) {
            if (allowed.equalsIgnoreCase(wanted)) {
                return allowed;   // normalise to the configured spelling
            }
        }
        throw new IllegalArgumentException("Not an available colour. Choose from: "
                + String.join(", ", config.allowedChatColors()));
    }

    // --- tokens (Phase 5) -------------------------------------------------------------------

    /** Outcome of a purchase attempt. */
    public enum PurchaseResult {
        BOUGHT,
        /** Already owned — not an error, and never charged twice. */
        ALREADY_OWNED,
        NOT_ENOUGH_TOKENS,
        /** The item id is not in the catalogue. */
        UNKNOWN_ITEM,
        /** Free items need no purchase. */
        FREE
    }

    /**
     * Tokens earned, derived from tenure rather than counted.
     *
     * <p>Deriving is what makes this safe. A stored balance can be double-credited by a retried
     * grant, drift after a crash between the write and the credit, or silently disagree with the
     * purchase history. {@code totalMonths × tokensPerMonth} cannot: it is a pure function of a
     * number the entitlement path already maintains carefully, and recomputing it always gives
     * the same answer. Same argument as {@code total_months} itself.
     */
    public int tokensEarned(UUID uuid) {
        return get(uuid).map(r -> r.totalMonths() * config.tokensPerMonth()).orElse(0);
    }

    public int tokensSpent(UUID uuid) {
        try {
            return storage.totalSpent(uuid);
        } catch (SQLException e) {
            throw new StorageException("Failed to read token spend for " + uuid, e);
        }
    }

    /**
     * Spendable tokens.
     *
     * <p>Clamped at zero: a chargeback removes tenure and so removes earned tokens, which can
     * leave somebody having spent more than they now have. Their unlocks are kept — never delete
     * something a player paid for — but they cannot buy again until tenure catches up.
     */
    public int tokenBalance(UUID uuid) {
        return Math.max(0, tokensEarned(uuid) - tokensSpent(uuid));
    }

    public boolean owns(UUID uuid, String itemId) {
        if (itemId == null) {
            return false;
        }
        if (config.trailCost(itemId) <= 0) {
            return true; // free items are owned by everyone
        }
        try {
            return storage.ownsUnlock(uuid, itemId);
        } catch (SQLException e) {
            throw new StorageException("Failed to read unlocks for " + uuid, e);
        }
    }

    public List<String> unlocks(UUID uuid) {
        try {
            return storage.unlocksFor(uuid);
        } catch (SQLException e) {
            throw new StorageException("Failed to list unlocks for " + uuid, e);
        }
    }

    /**
     * Buys a trail with tokens.
     *
     * <p>The whole thing runs in one transaction and the affordability check is re-read inside
     * it, so two commands racing cannot both pass the check and both spend.
     */
    /**
     * Buys a skin unlock. Same machinery as trails — the composite primary key on
     * {@code supporter_unlocks} refuses a double charge at the database — with one namespace
     * twist: the unlock is recorded as {@code skin:<name>}, because skin names and trail names
     * collide ("gold" is both a trail and a tint) and an unlock of one must never grant the
     * other.
     *
     * <p>The cost comes from the caller, because pricing needs to know whether the name is a
     * tint or a costume and that catalogue lives in the UI layer — the same layering reason
     * {@code setSkin} does not validate names.
     */
    public synchronized PurchaseResult purchaseSkin(UUID uuid, String skinName, int cost) {
        Objects.requireNonNull(uuid, "uuid");
        if (skinName == null || skinName.isBlank()) {
            return PurchaseResult.UNKNOWN_ITEM;
        }
        if (cost <= 0) {
            return PurchaseResult.FREE;
        }
        String unlockId = "skin:" + skinName.toLowerCase();
        try {
            return storage.transact(() -> {
                if (storage.ownsUnlock(uuid, unlockId)) {
                    return PurchaseResult.ALREADY_OWNED;
                }
                int balance = Math.max(0, tokensEarned(uuid) - storage.totalSpent(uuid));
                if (balance < cost) {
                    return PurchaseResult.NOT_ENOUGH_TOKENS;
                }
                long now = clock.millis();
                if (!storage.addUnlock(uuid, unlockId, cost, now)) {
                    return PurchaseResult.ALREADY_OWNED; // lost a race; nothing charged
                }
                storage.log(uuid, directory.usernameFor(uuid).orElse(null),
                        "PURCHASE", unlockId + " for " + cost + " tokens", "player", now);
                return PurchaseResult.BOUGHT;
            });
        } catch (SQLException e) {
            throw new StorageException("Failed to purchase skin " + skinName, e);
        }
    }

    /**
     * Buys a wearable unlock — a cape design, a hat. Third user of the trail machinery, third
     * namespace: {@code gear:<name>}, because wearable design names live in their own catalogue
     * and must never collide with a trail or a skin unlock ("gold" is already two of the
     * three). Cost comes from the caller for the same layering reason as skins — the gear
     * catalogue lives in the command layer.
     */
    public synchronized PurchaseResult purchaseGear(UUID uuid, String gearName, int cost) {
        Objects.requireNonNull(uuid, "uuid");
        if (gearName == null || gearName.isBlank()) {
            return PurchaseResult.UNKNOWN_ITEM;
        }
        if (cost <= 0) {
            return PurchaseResult.FREE;
        }
        String unlockId = "gear:" + gearName.toLowerCase();
        try {
            return storage.transact(() -> {
                if (storage.ownsUnlock(uuid, unlockId)) {
                    return PurchaseResult.ALREADY_OWNED;
                }
                int balance = Math.max(0, tokensEarned(uuid) - storage.totalSpent(uuid));
                if (balance < cost) {
                    return PurchaseResult.NOT_ENOUGH_TOKENS;
                }
                long now = clock.millis();
                if (!storage.addUnlock(uuid, unlockId, cost, now)) {
                    return PurchaseResult.ALREADY_OWNED; // lost a race; nothing charged
                }
                storage.log(uuid, directory.usernameFor(uuid).orElse(null),
                        "PURCHASE", unlockId + " for " + cost + " tokens", "player", now);
                return PurchaseResult.BOUGHT;
            });
        } catch (SQLException e) {
            throw new StorageException("Failed to purchase gear " + gearName, e);
        }
    }

    /** Whether this player may have a wearable that costs {@code cost}. Free means yes. */
    public boolean ownsGear(UUID uuid, String gearName, int cost) {
        if (cost <= 0) {
            return true;
        }
        try {
            return storage.ownsUnlock(uuid, "gear:" + gearName.toLowerCase());
        } catch (SQLException e) {
            throw new StorageException("Failed to check gear unlock " + gearName, e);
        }
    }

    /** Whether this player may wear a skin that costs {@code cost}. Free means yes. */
    public boolean ownsSkin(UUID uuid, String skinName, int cost) {
        if (cost <= 0) {
            return true;
        }
        try {
            return storage.ownsUnlock(uuid, "skin:" + skinName.toLowerCase());
        } catch (SQLException e) {
            throw new StorageException("Failed to check skin unlock " + skinName, e);
        }
    }

    public synchronized PurchaseResult purchaseTrail(UUID uuid, String trailId) {
        Objects.requireNonNull(uuid, "uuid");
        if (trailId == null || !config.trails().containsKey(trailId)) {
            return PurchaseResult.UNKNOWN_ITEM;
        }
        int cost = config.trailCost(trailId);
        if (cost <= 0) {
            return PurchaseResult.FREE;
        }
        try {
            return storage.transact(() -> {
                if (storage.ownsUnlock(uuid, trailId)) {
                    return PurchaseResult.ALREADY_OWNED;
                }
                int balance = Math.max(0,
                        tokensEarned(uuid) - storage.totalSpent(uuid));
                if (balance < cost) {
                    return PurchaseResult.NOT_ENOUGH_TOKENS;
                }
                long now = clock.millis();
                if (!storage.addUnlock(uuid, trailId, cost, now)) {
                    return PurchaseResult.ALREADY_OWNED; // lost a race; nothing charged
                }
                storage.log(uuid, directory.usernameFor(uuid).orElse(null),
                        "PURCHASE", trailId + " for " + cost + " tokens", "player", now);
                return PurchaseResult.BOUGHT;
            });
        } catch (SQLException e) {
            throw new StorageException("Purchase failed for " + uuid, e);
        }
    }

    /**
     * Removes tenure that was never actually paid for.
     *
     * <p>This is the answer to PLAN.md open decision 3, "chargeback vs expiry". They are not the
     * same event and must not behave the same way:
     *
     * <ul>
     *   <li>An <b>expiry or revoke</b> ends entitlement and leaves tenure alone. The player did
     *       pay for that time, so they keep the tokens it earned.
     *   <li>A <b>chargeback</b> means the money came back. The tenure was never paid for, so it
     *       is removed — and because the balance is derived from tenure, the tokens go with it
     *       automatically. There is no separate token ledger to keep in step.
     * </ul>
     *
     * <p>Unlocks already bought are NOT removed. Clawing back an item somebody is using is a
     * support argument nobody wins, and {@link #tokenBalance} clamps at zero, so the effect is
     * simply that they cannot buy anything more until tenure catches up.
     */
    public synchronized void chargeback(UUID uuid, int days, String actor) {
        Objects.requireNonNull(uuid, "uuid");
        if (days <= 0) {
            throw new IllegalArgumentException("days must be positive, got " + days);
        }
        try {
            storage.transact(() -> {
                Optional<SupporterRecord> existing = storage.find(uuid);
                if (existing.isEmpty()) {
                    return null;
                }
                SupporterRecord r = existing.get();
                long now = clock.millis();
                int remaining = Math.max(0, r.totalDays() - days);
                SupporterRecord updated = new SupporterRecord(
                        r.uuid(), r.username(), false, r.firstGrantedAt(), r.lastGrantedAt(),
                        Math.min(r.expiresAt(), now), Math.min(r.graceUntil(), now),
                        remaining, r.source());
                storage.upsert(updated);
                cache.remove(uuid);
                storage.log(uuid, r.username(), "CHARGEBACK",
                        days + "d removed, tenure now " + remaining + "d", actor, now);
                log.warn("Chargeback for " + uuid + ": " + days + "d removed by " + actor);
                return null;
            });
            syncPermissions(uuid);
        } catch (SQLException e) {
            throw new StorageException("Chargeback failed for " + uuid, e);
        }
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
            GrantResult result = storage.transact(() -> applyGrant(uuid, username, days, source, txn));
            syncPermissions(uuid);
            announceIfNew(result, username);
            return result;
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
            GrantResult outcome = storage.transact(() -> {
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
                // Second duplicate check, against the queue this time. The ledger check above
                // only catches a transaction that has been DELIVERED; one that is still waiting
                // for its player to log in lives in pending_grants and nowhere else. Without
                // this, a provider retry adds a second pending row.
                if (txn != null && !txn.isBlank() && storage.pendingTxnExists(txn)) {
                    log.warn("Ignoring retry of transaction " + txn + " — already queued for "
                            + username);
                    return duplicate(null);
                }
                long now = clock.millis();
                storage.addPendingGrant(username, days, source, txn, now);
                storage.log(null, username, "GRANT_QUEUED",
                        days + "d pending login", source, now);
                log.info("Queued " + days + "d for unresolved username " + username);
                return new GrantResult(GrantResult.Outcome.QUEUED_PENDING, null, 0);
            });
            // A queued grant has no uuid yet; it syncs when the player next logs in.
            if (outcome.record() != null) {
                syncPermissions(outcome.record().uuid());
            }
            announceIfNew(outcome, username);
            return outcome;
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
            syncPermissions(uuid);
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
                syncPermissions(uuid);
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

        // Login is the natural moment to re-assert permissions, and the only moment a queued
        // grant can be synced at all — it had no uuid when it was bought. It also self-heals:
        // if a node was ever lost (LuckPerms down during a grant, a manual edit, a restore from
        // backup), the player's next login puts it back.
        syncPermissions(uuid);

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
