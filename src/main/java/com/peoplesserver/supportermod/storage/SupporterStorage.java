package com.peoplesserver.supportermod.storage;

import com.peoplesserver.supportermod.core.SupporterIdentity;
import com.peoplesserver.supportermod.core.SupporterRecord;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for supporter entitlement.
 *
 * <p>Per the spec's standing instruction, only {@code SupporterService} may call this class —
 * nothing else reads or writes the supporter table.
 */
public final class SupporterStorage implements AutoCloseable {

    /** A queued grant awaiting a UUID. */
    public record PendingGrant(long id, String usernameLower, int days, String source, String txn) {}

    private final Database database;

    private SupporterStorage(Database database) {
        this.database = database;
    }

    public static SupporterStorage open(Path file) throws SQLException {
        Database db = Database.open(file);
        try {
            Migrations.apply(db.connection());
        } catch (SQLException e) {
            db.close();
            throw e;
        }
        return new SupporterStorage(db);
    }

    private Connection conn() {
        return database.connection();
    }

    // --- supporters -----------------------------------------------------------------------

    public synchronized Optional<SupporterRecord> find(UUID uuid) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement("SELECT * FROM supporters WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    public synchronized Optional<SupporterRecord> findByUsername(String username)
            throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement("SELECT * FROM supporters WHERE username_lower = ?")) {
            ps.setString(1, lower(username));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    /** All rows still flagged active, regardless of expiry. Used to warm the cache. */
    public synchronized List<SupporterRecord> findActive() throws SQLException {
        return query("SELECT * FROM supporters WHERE active = 1");
    }

    /** Active rows whose grace window has closed — the reconcile work list. */
    public synchronized List<SupporterRecord> findLapsed(long nowMs) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        "SELECT * FROM supporters WHERE active = 1 AND grace_until <= ?")) {
            ps.setLong(1, nowMs);
            try (ResultSet rs = ps.executeQuery()) {
                List<SupporterRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(read(rs));
                }
                return out;
            }
        }
    }

    /** Active supporters ordered by tenure, for {@code /supporters}. */
    public synchronized List<SupporterRecord> findActiveByTenure() throws SQLException {
        return query("SELECT * FROM supporters WHERE active = 1 ORDER BY total_days DESC");
    }

    public synchronized void upsert(SupporterRecord r) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        """
                        INSERT INTO supporters (uuid, username, username_lower, active,
                            first_granted_at, last_granted_at, expires_at, grace_until,
                            total_days, source)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(uuid) DO UPDATE SET
                            username = excluded.username,
                            username_lower = excluded.username_lower,
                            active = excluded.active,
                            last_granted_at = excluded.last_granted_at,
                            expires_at = excluded.expires_at,
                            grace_until = excluded.grace_until,
                            total_days = excluded.total_days,
                            source = excluded.source
                        """)) {
            ps.setString(1, r.uuid().toString());
            ps.setString(2, r.username());
            ps.setString(3, lower(r.username()));
            ps.setInt(4, r.active() ? 1 : 0);
            ps.setLong(5, r.firstGrantedAt());
            ps.setLong(6, r.lastGrantedAt());
            ps.setLong(7, r.expiresAt());
            ps.setLong(8, r.graceUntil());
            ps.setInt(9, r.totalDays());
            ps.setString(10, r.source());
            ps.executeUpdate();
        }
    }

    // --- audit log ------------------------------------------------------------------------

    public synchronized void log(
            UUID uuid, String username, String action, String detail, String actor, long atMs)
            throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        """
                        INSERT INTO supporter_log (uuid, username, action, detail, actor, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
            ps.setString(1, uuid == null ? null : uuid.toString());
            ps.setString(2, username);
            ps.setString(3, action);
            ps.setString(4, detail);
            ps.setString(5, actor);
            ps.setLong(6, atMs);
            ps.executeUpdate();
        }
    }

    /** Audit trail for one player, newest first. */
    public synchronized List<String> logFor(UUID uuid, int limit) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        """
                        SELECT created_at, action, detail, actor FROM supporter_log
                        WHERE uuid = ? ORDER BY id DESC LIMIT ?
                        """)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getLong(1) + " " + rs.getString(2)
                            + " " + rs.getString(3) + " (" + rs.getString(4) + ")");
                }
                return out;
            }
        }
    }

    public synchronized int countLogEntries(UUID uuid, String action) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        "SELECT COUNT(*) FROM supporter_log WHERE uuid = ? AND action = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, action);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // --- transaction ledger ---------------------------------------------------------------

    public synchronized boolean txnExists(String txn) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement("SELECT 1 FROM supporter_txn WHERE txn = ?")) {
            ps.setString(1, txn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Records a delivered transaction.
     *
     * @return false if it was already present, meaning another thread won the race and the
     *     caller must not apply the grant
     */
    public synchronized boolean recordTxn(String txn, UUID uuid, int days, String source, long atMs)
            throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        """
                        INSERT OR IGNORE INTO supporter_txn (txn, uuid, days, source, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
            ps.setString(1, txn);
            ps.setString(2, uuid == null ? null : uuid.toString());
            ps.setInt(3, days);
            ps.setString(4, source);
            ps.setLong(5, atMs);
            return ps.executeUpdate() > 0;
        }
    }

    // --- pending grants -------------------------------------------------------------------

    /**
     * True if this transaction is already sitting in the queue, claimed or not.
     *
     * <p>Needed because a queued grant is NOT written to {@code supporter_txn} — it cannot be,
     * since the ledger records a delivery and a queued grant has not been delivered yet (it has
     * no uuid to record against, and writing it early would make the claim at login look like a
     * duplicate and silently drop the purchase). So the queue is its own duplicate check, and a
     * provider retry has to be tested against both.
     */
    public synchronized boolean pendingTxnExists(String txn) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement("SELECT 1 FROM pending_grants WHERE txn = ?")) {
            ps.setString(1, txn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized void addPendingGrant(
            String username, int days, String source, String txn, long atMs) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        """
                        INSERT INTO pending_grants (username_lower, days, source, txn, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
            ps.setString(1, lower(username));
            ps.setInt(2, days);
            ps.setString(3, source);
            ps.setString(4, txn);
            ps.setLong(5, atMs);
            ps.executeUpdate();
        }
    }

    public synchronized List<PendingGrant> unclaimedGrants(String username) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        """
                        SELECT id, username_lower, days, source, txn FROM pending_grants
                        WHERE username_lower = ? AND claimed_at IS NULL ORDER BY id
                        """)) {
            ps.setString(1, lower(username));
            try (ResultSet rs = ps.executeQuery()) {
                List<PendingGrant> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new PendingGrant(
                            rs.getLong(1), rs.getString(2), rs.getInt(3),
                            rs.getString(4), rs.getString(5)));
                }
                return out;
            }
        }
    }

    public synchronized void markClaimed(long id, long atMs) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        "UPDATE pending_grants SET claimed_at = ? WHERE id = ?")) {
            ps.setLong(1, atMs);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    // --- identity (Phase 2) ---------------------------------------------------------------

    /** Identity for a player, or {@link SupporterIdentity#NONE} if they have never set one. */
    public synchronized SupporterIdentity findIdentity(UUID uuid) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        """
                        SELECT title, chat_color, trail, skin, pet, hide_trails
                        FROM supporter_identity WHERE uuid = ?
                        """)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return SupporterIdentity.NONE;
                }
                return new SupporterIdentity(
                        rs.getString("title"),
                        rs.getString("chat_color"),
                        rs.getString("trail"),
                        rs.getString("skin"),
                        rs.getString("pet"),
                        rs.getInt("hide_trails") != 0);
            }
        }
    }

    /**
     * Writes a player's identity.
     *
     * <p>Upsert rather than insert-or-update-in-two-steps: a player changing their title twice
     * in quick succession must not be able to race two rows into a table keyed by uuid.
     */
    public synchronized void saveIdentity(UUID uuid, SupporterIdentity identity, long atMs)
            throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        """
                        INSERT INTO supporter_identity
                            (uuid, title, chat_color, trail, skin, pet, hide_trails, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(uuid) DO UPDATE SET
                            title = excluded.title,
                            chat_color = excluded.chat_color,
                            trail = excluded.trail,
                            skin = excluded.skin,
                            pet = excluded.pet,
                            hide_trails = excluded.hide_trails,
                            updated_at = excluded.updated_at
                        """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, identity.title());
            ps.setString(3, identity.chatColor());
            ps.setString(4, identity.trail());
            ps.setString(5, identity.skin());
            ps.setString(6, identity.pet());
            ps.setInt(7, identity.hideTrails() ? 1 : 0);
            ps.setLong(8, atMs);
            ps.executeUpdate();
        }
    }

    // --- unlocks / tokens (Phase 5) ---------------------------------------------------------

    /** Tokens this player has spent — the sum of what they actually bought. */
    public synchronized int totalSpent(UUID uuid) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        "SELECT COALESCE(SUM(cost), 0) FROM supporter_unlocks WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public synchronized boolean ownsUnlock(UUID uuid, String itemId) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        "SELECT 1 FROM supporter_unlocks WHERE uuid = ? AND item_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized List<String> unlocksFor(UUID uuid) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        "SELECT item_id FROM supporter_unlocks WHERE uuid = ? ORDER BY unlocked_at")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        }
    }

    /**
     * Records a purchase.
     *
     * <p>{@code DO NOTHING} on conflict, and the caller checks the row count: a double-click or
     * a retried command cannot charge twice, because the composite primary key refuses the
     * second row rather than a check that could race against itself.
     *
     * @return true if this call actually bought it
     */
    public synchronized boolean addUnlock(UUID uuid, String itemId, int cost, long atMs)
            throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        """
                        INSERT INTO supporter_unlocks(uuid, item_id, cost, unlocked_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(uuid, item_id) DO NOTHING
                        """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, itemId);
            ps.setInt(3, cost);
            ps.setLong(4, atMs);
            return ps.executeUpdate() > 0;
        }
    }

    // --- quests / token grants (0.21.0) -----------------------------------------------------

    /** Tokens granted outside tenure — quest rewards. Part of the derived "earned" side. */
    public synchronized int totalTokenGrants(UUID uuid) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        "SELECT COALESCE(SUM(amount), 0) FROM supporter_token_grants"
                                + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Records a token grant. Same idempotency shape as {@link #addUnlock}: the composite key
     * refuses a second row, so a retried claim credits once.
     *
     * @return true if this call actually granted it
     */
    public synchronized boolean addTokenGrant(UUID uuid, String grantId, int amount, long atMs)
            throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        """
                        INSERT INTO supporter_token_grants(uuid, grant_id, amount, granted_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(uuid, grant_id) DO NOTHING
                        """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, grantId);
            ps.setInt(3, amount);
            ps.setLong(4, atMs);
            return ps.executeUpdate() > 0;
        }
    }

    /** One player's progress on one quest. Null claimedAt means unclaimed. */
    public record QuestRow(String questKey, int progress, int target, String marker,
                           Long claimedAt) {}

    public synchronized QuestRow questRow(UUID uuid, String questKey) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        "SELECT progress, target, marker, claimed_at FROM supporter_quests"
                                + " WHERE uuid = ? AND quest_key = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, questKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                // wasNull() reports on the LAST column read, so it must be checked before any
                // other getter runs — the first version checked it after reading three more
                // columns and reported the marker's nullness instead of the claim's.
                long claimed = rs.getLong(4);
                boolean unclaimed = rs.wasNull();
                return new QuestRow(questKey, rs.getInt(1), rs.getInt(2), rs.getString(3),
                        unclaimed ? null : claimed);
            }
        }
    }

    /** Adds progress, capped at target in the UPSERT so no reader ever sees an overshoot. */
    public synchronized void addQuestProgress(UUID uuid, String questKey, int amount, int target,
                                              long atMs) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        """
                        INSERT INTO supporter_quests(uuid, quest_key, progress, target, updated_at)
                        VALUES (?, ?, MIN(?, ?), ?, ?)
                        ON CONFLICT(uuid, quest_key) DO UPDATE SET
                            progress = MIN(target, progress + ?),
                            updated_at = ?
                        """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, questKey);
            ps.setInt(3, amount);
            ps.setInt(4, target);
            ps.setInt(5, target);
            ps.setLong(6, atMs);
            ps.setInt(7, amount);
            ps.setLong(8, atMs);
            ps.executeUpdate();
        }
    }

    /**
     * Counts today once for a day-counting quest: progress moves only when {@code day} differs
     * from the stored marker. Single-writer (the quest tick thread), so the read-modify-write
     * inside the UPSERT is not racing anything.
     */
    public synchronized void markQuestDay(UUID uuid, String questKey, String day, int target,
                                          long atMs) throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        """
                        INSERT INTO supporter_quests(uuid, quest_key, progress, target, marker,
                                                     updated_at)
                        VALUES (?, ?, 1, ?, ?, ?)
                        ON CONFLICT(uuid, quest_key) DO UPDATE SET
                            progress = CASE WHEN marker IS ?
                                THEN progress ELSE MIN(target, progress + 1) END,
                            marker = ?,
                            updated_at = ?
                        """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, questKey);
            ps.setInt(3, target);
            ps.setString(4, day);
            ps.setLong(5, atMs);
            ps.setString(6, day);
            ps.setString(7, day);
            ps.setLong(8, atMs);
            ps.executeUpdate();
        }
    }

    /**
     * Marks a quest claimed — only if it is complete and not already claimed, guarded in the
     * WHERE clause rather than by a check that could race.
     *
     * @return true if this call actually claimed it
     */
    public synchronized boolean claimQuest(UUID uuid, String questKey, long atMs)
            throws SQLException {
        try (PreparedStatement ps =
                conn().prepareStatement(
                        "UPDATE supporter_quests SET claimed_at = ? WHERE uuid = ?"
                                + " AND quest_key = ? AND claimed_at IS NULL"
                                + " AND progress >= target")) {
            ps.setLong(1, atMs);
            ps.setString(2, uuid.toString());
            ps.setString(3, questKey);
            return ps.executeUpdate() > 0;
        }
    }

    // --- transactions ---------------------------------------------------------------------

    /** Body of a transaction. */
    @FunctionalInterface
    public interface TxnBody<T> {
        T run() throws SQLException;
    }

    /**
     * Runs {@code body} in a single transaction.
     *
     * <p>A grant writes the supporter row, the transaction ledger and the audit log. Those
     * three must land together — a crash between the row update and the ledger insert would
     * leave a purchase that could be delivered twice.
     */
    public synchronized <T> T transact(TxnBody<T> body) throws SQLException {
        Connection c = conn();
        boolean previous = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T result = body.run();
            c.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(previous);
        }
    }

    // --- helpers --------------------------------------------------------------------------

    private List<SupporterRecord> query(String sql) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            List<SupporterRecord> out = new ArrayList<>();
            while (rs.next()) {
                out.add(read(rs));
            }
            return out;
        }
    }

    private static SupporterRecord read(ResultSet rs) throws SQLException {
        return new SupporterRecord(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("username"),
                rs.getInt("active") == 1,
                rs.getLong("first_granted_at"),
                rs.getLong("last_granted_at"),
                rs.getLong("expires_at"),
                rs.getLong("grace_until"),
                rs.getInt("total_days"),
                rs.getString("source"));
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    @Override
    public void close() throws SQLException {
        database.close();
    }
}
