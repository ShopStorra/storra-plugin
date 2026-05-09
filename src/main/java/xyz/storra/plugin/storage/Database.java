package xyz.storra.plugin.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite connection manager.
 *
 * One connection per plugin lifetime — SQLite serializes writes
 * anyway, so a connection pool buys nothing for our workload (one
 * MC server, low write throughput, tens of rows per minute on a
 * busy server). WAL mode lets reads proceed while a write is in
 * flight, which matters for `/storra status` polling
 * `delivery_history` while the poll loop appends.
 *
 * Schema is created idempotently on open() so the plugin
 * self-heals if the data folder is wiped or migrated.
 */
public final class Database implements AutoCloseable {

    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    public static Database open(JavaPlugin plugin, Path dbPath) throws SQLException {
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (Exception ex) {
            throw new SQLException("Failed to create plugin data folder", ex);
        }

        // sqlite-jdbc registers the driver via static block — but
        // some classloaders require an explicit Class.forName. Cheap
        // safety call.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("sqlite-jdbc not on classpath", ex);
        }

        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        Connection conn = DriverManager.getConnection(url);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = NORMAL");
            st.execute("PRAGMA foreign_keys = ON");
            st.execute(
                "CREATE TABLE IF NOT EXISTS delivery_offline_queue (" +
                "  task_id INTEGER PRIMARY KEY, " +
                "  player_uuid TEXT NOT NULL, " +
                "  command TEXT NOT NULL, " +
                "  queued_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)" +
                ")"
            );
            st.execute(
                "CREATE INDEX IF NOT EXISTS delivery_offline_queue_player_uuid_idx " +
                "ON delivery_offline_queue(player_uuid)"
            );
            st.execute(
                "CREATE TABLE IF NOT EXISTS delivery_history (" +
                "  task_id INTEGER PRIMARY KEY, " +
                "  status TEXT NOT NULL, " +
                "  player_name TEXT, " +
                "  command TEXT, " +
                "  reason TEXT, " +
                "  recorded_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)" +
                ")"
            );
            st.execute(
                "CREATE INDEX IF NOT EXISTS delivery_history_recorded_at_idx " +
                "ON delivery_history(recorded_at DESC)"
            );
        }
        plugin.getLogger().info("SQLite ready at " + dbPath);
        return new Database(conn);
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        if (!connection.isClosed()) {
            connection.close();
        }
    }
}
