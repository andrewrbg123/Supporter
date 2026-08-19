package com.peoplesserver.supportermod.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Versioned schema migrations.
 *
 * <p>SUPPORTERMOD_SPEC.md §2.1 defines six tables. Only the ones Phase 1 actually needs are
 * created here; inventing the other five without the spec would guarantee a conflict later.
 * They arrive as {@code V2} and beyond once §2.1 is available, which is the whole reason this
 * runs as numbered migrations rather than a fixed {@code CREATE TABLE} block.
 */
final class Migrations {

    private Migrations() {}

    /** Each entry is one version; index 0 is version 1. Never edit a shipped entry. */
    private static final List<String[]> VERSIONS = List.<String[]>of(
            new String[] {
                """
                CREATE TABLE supporters (
                    uuid             TEXT    PRIMARY KEY,
                    username         TEXT,
                    username_lower   TEXT,
                    active           INTEGER NOT NULL DEFAULT 0,
                    first_granted_at INTEGER NOT NULL,
                    last_granted_at  INTEGER NOT NULL,
                    expires_at       INTEGER NOT NULL,
                    grace_until      INTEGER NOT NULL,
                    total_days       INTEGER NOT NULL DEFAULT 0,
                    source           TEXT
                )
                """,
                "CREATE INDEX idx_supporters_username ON supporters(username_lower)",
                // Drives the reconcile sweep, which is the only full-table query we run.
                "CREATE INDEX idx_supporters_sweep ON supporters(active, grace_until)",
                """
                CREATE TABLE supporter_log (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid       TEXT,
                    username   TEXT,
                    action     TEXT    NOT NULL,
                    detail     TEXT,
                    actor      TEXT,
                    created_at INTEGER NOT NULL
                )
                """,
                "CREATE INDEX idx_log_uuid ON supporter_log(uuid, created_at)",
                """
                -- Idempotency ledger for payment-provider deliveries. The PRIMARY KEY is the
                -- entire point: Tebex retries a command until the server acknowledges it, so
                -- without this one purchase can be delivered several times.
                CREATE TABLE supporter_txn (
                    txn        TEXT    PRIMARY KEY,
                    uuid       TEXT,
                    days       INTEGER NOT NULL,
                    source     TEXT,
                    created_at INTEGER NOT NULL
                )
                """,
                """
                -- Grants for a username the server could not resolve to a UUID yet, e.g. a gift
                -- purchase for someone who has never logged in. Claimed on their next login.
                CREATE TABLE pending_grants (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    username_lower TEXT    NOT NULL,
                    days           INTEGER NOT NULL,
                    source         TEXT,
                    txn            TEXT,
                    created_at     INTEGER NOT NULL,
                    claimed_at     INTEGER
                )
                """,
                "CREATE INDEX idx_pending_username ON pending_grants(username_lower, claimed_at)"
            },
            // --- V2: Phase 2 identity ---------------------------------------------------
            new String[] {
                """
                -- Chat identity: the custom title and chat colour a supporter has chosen.
                --
                -- A SEPARATE TABLE from supporters, deliberately. Identity is something the
                -- player configured, not part of their entitlement arithmetic, and it must
                -- outlive a lapse: when somebody's rank expires their title stops RENDERING
                -- but is not deleted, so renewing restores exactly what they had rather than
                -- making them set it up again. That is the standing rule — never delete
                -- something a player paid for, freeze it instead.
                --
                -- No index: this is only ever read by primary key, on login and on /supporter
                -- title|colour. The chat path reads the in-memory cache, never this table.
                CREATE TABLE supporter_identity (
                    uuid       TEXT PRIMARY KEY,
                    title      TEXT,
                    chat_color TEXT,
                    updated_at INTEGER NOT NULL
                )
                """
            },
            // --- V3: Phase 4 trails ------------------------------------------------------
            new String[] {
                // The chosen trail, by config id rather than by particle effect name — so an
                // admin can repoint "ember" at a different effect without rewriting rows.
                "ALTER TABLE supporter_identity ADD COLUMN trail TEXT",
                // Whether this player wants OTHER people's trails hidden. Not a supporter
                // perk: anybody may opt out, which is why it lives on the same row rather
                // than being inferred from entitlement. A player who has never been a
                // supporter simply gets a row with only this column set.
                "ALTER TABLE supporter_identity ADD COLUMN hide_trails INTEGER NOT NULL DEFAULT 0"
            },
            // --- V4: Phase 5 tokens ------------------------------------------------------
            new String[] {
                """
                -- Things a player has bought with tokens.
                --
                -- There is deliberately NO balance column anywhere. The balance is derived:
                -- earned (from tenure) minus the sum of these costs. A stored counter can
                -- drift, be double-credited on a retry, or disagree with the purchase history
                -- after a crash; a derived one cannot. Same reasoning as total_months.
                --
                -- The composite PRIMARY KEY is what makes a purchase idempotent: buying the
                -- same item twice is refused by the database, not by a check that could race.
                --
                -- Rows are never deleted. An expiry, a revoke and a chargeback all leave
                -- unlocks alone — never delete something a player paid for.
                CREATE TABLE supporter_unlocks (
                    uuid        TEXT    NOT NULL,
                    item_id     TEXT    NOT NULL,
                    cost        INTEGER NOT NULL,
                    unlocked_at INTEGER NOT NULL,
                    PRIMARY KEY (uuid, item_id)
                )
                """
            },
            // --- V5: v0.17.0 body skins ---------------------------------------------------
            new String[] {
                // The chosen body-tint skin, re-applied at login. Same rules as every other
                // identity column: it outlives a lapse (stops applying, is not deleted), and
                // a name no longer in the catalogue is ignored rather than an error.
                "ALTER TABLE supporter_identity ADD COLUMN skin TEXT"
            },
            // --- V6: v0.21.0 quests -------------------------------------------------------
            new String[] {
                """
                -- Tokens earned OUTSIDE tenure — quest rewards today, anything else later.
                --
                -- This extends the derived-balance rule rather than breaking it: earned is now
                -- tenure PLUS the sum of this ledger, and the balance still has no stored
                -- counter anywhere. The composite PRIMARY KEY makes every grant idempotent the
                -- same way purchases are: a grant id can land once, so a double-claim or a
                -- crash-retry cannot credit twice. Rows are never deleted — a chargeback
                -- removes tenure, not quest earnings, because these were earned by playing.
                CREATE TABLE supporter_token_grants (
                    uuid       TEXT    NOT NULL,
                    grant_id   TEXT    NOT NULL,
                    amount     INTEGER NOT NULL,
                    granted_at INTEGER NOT NULL,
                    PRIMARY KEY (uuid, grant_id)
                )
                """,
                """
                -- Per-player quest progress, keyed by a date-scoped quest key
                -- ("d:2026-08-19:play30", "w:2026-W34:wdays4"), so a new day or week simply
                -- starts writing new rows and old ones become inert history. progress is
                -- capped at target in the UPSERT; marker is the last UTC date counted, used
                -- by day-counting quests to count each day once.
                CREATE TABLE supporter_quests (
                    uuid       TEXT    NOT NULL,
                    quest_key  TEXT    NOT NULL,
                    progress   INTEGER NOT NULL DEFAULT 0,
                    target     INTEGER NOT NULL,
                    marker     TEXT,
                    claimed_at INTEGER,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (uuid, quest_key)
                )
                """
            },
            // --- V7: v0.21.4 "top" renamed to "top-hat" -----------------------------------
            new String[] {
                // A design rename moves the unlocks with it, or the rename silently takes
                // away something people paid 150 tokens for. The item id never changed —
                // only the catalogue name players type and buy under.
                "UPDATE supporter_unlocks SET item_id = 'gear:top-hat'"
                        + " WHERE item_id = 'gear:top'"
            });

    static void apply(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version    INTEGER PRIMARY KEY,
                        applied_at INTEGER NOT NULL
                    )
                    """);
        }

        int current = currentVersion(conn);
        boolean autoCommit = conn.getAutoCommit();
        for (int version = current + 1; version <= VERSIONS.size(); version++) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String sql : VERSIONS.get(version - 1)) {
                    st.execute(sql);
                }
                st.execute(
                        "INSERT INTO schema_version(version, applied_at) VALUES ("
                                + version + ", " + System.currentTimeMillis() + ")");
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new SQLException("Migration to schema version " + version + " failed", e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    private static int currentVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
