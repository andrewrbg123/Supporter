package com.peoplesserver.supportermod.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite connection holder.
 *
 * <p>One connection, guarded by the callers in {@link SupporterStorage}. A pool would buy
 * nothing here: entitlement reads on the hot path (chat, every tick) go through the in-memory
 * cache in {@code SupporterService}, so the database only sees logins, grants and the nightly
 * reconcile.
 */
public final class Database implements AutoCloseable {

    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    public static Database open(Path file) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            // WAL survives an unclean server stop without losing committed grants, and lets
            // the nightly reconcile write while reads continue.
            st.execute("PRAGMA journal_mode=WAL");
            // NORMAL is the right trade with WAL: a commit is durable across a process crash,
            // only a host power loss can lose the last transactions.
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return new Database(conn);
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        if (!connection.isClosed()) {
            // Fold the WAL back into the main database file so a backup of the .db alone is
            // complete. Without this a copied .db can be missing recent grants.
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException ignored) {
                // Best effort — never block shutdown on a checkpoint.
            }
            connection.close();
        }
    }
}
