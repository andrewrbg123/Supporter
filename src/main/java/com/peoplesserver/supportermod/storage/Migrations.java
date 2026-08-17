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
