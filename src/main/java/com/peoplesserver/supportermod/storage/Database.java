package com.peoplesserver.supportermod.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite connection holder.
 *
 * <p>One connection, guarded by the callers in {@link SupporterStorage}. A pool would buy
 * nothing here: entitlement reads on the hot path (chat, every tick) go through the in-memory
 * cache in {@code SupporterService}, so the database only sees logins, grants and the nightly
 * reconcile.
 *
 * <p><b>Connects through {@link SQLiteDataSource} rather than {@code DriverManager}, and that
 * is not a style choice.</b> The first live deploy failed with:
 *
 * <pre>
 * java.sql.SQLException: No suitable driver found for jdbc:sqlite:/home/container/...
 * </pre>
 *
 * <p>The driver was present and correctly shaded — {@code META-INF/services/java.sql.Driver}
 * was in the jar. The problem is that {@code DriverManager} performs its {@code ServiceLoader}
 * discovery once, against the system classloader, and Hytale loads each plugin in its own
 * {@code PluginClassLoader}. The system classloader cannot see plugin jars, so the driver is
 * never registered and {@code DriverManager.getConnection} has nothing to hand back.
 *
 * <p>A {@code DataSource} holds the driver directly and never consults that global registry, so
 * the classloader question does not arise. It also keeps us out of the way of the other plugins
 * on this server that shade their own copy of sqlite-jdbc — Windskull's Survival, Hunger and
 * Thirst all do — rather than adding a fourth driver to a registry shared between them.
 */
public final class Database implements AutoCloseable {

    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    public static Database open(Path file) throws SQLException {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        Connection conn = source.getConnection();
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
