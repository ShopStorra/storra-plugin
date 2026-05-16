package xyz.storra.plugin.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    /**
     * Test-only factory. Wraps an existing JDBC connection; caller
     * is responsible for the schema bootstrap (tests typically
     * run a subset of the open() DDL against a temp file). Not
     * called from production paths.
     */
    public static Database fromConnectionForTests(Connection connection) {
        return new Database(connection);
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

            // One-time schema migrations:
            //   v0.x → v0.2: task_id INTEGER → command_id TEXT
            //   v0.2 → v0.3: player_uuid → player_name_lower (the
            //     offline queue is now keyed by lowercased player
            //     name, since the v2 wire contract sends name not UUID)
            //   v0.3 → v0.4: add product_name so the delivery
            //     receipt can name the package on drain.
            // All wipes are safe — local rows are ephemera. Storra's
            // delivery_queue is canonical; any un-confirmed task gets
            // re-emitted on the next /pending pull.
            boolean needsMigration =
                hasLegacyTaskIdColumn(conn, "delivery_offline_queue")
                || hasLegacyTaskIdColumn(conn, "delivery_history")
                || hasLegacyColumn(conn, "delivery_offline_queue", "player_uuid")
                || (tableExists(conn, "delivery_offline_queue")
                    && !hasLegacyColumn(conn, "delivery_offline_queue", "product_name"));
            if (needsMigration) {
                plugin.getLogger().info(
                    "Migrating SQLite schema: dropping legacy offline-queue / history tables. " +
                    "Pending deliveries are re-pulled from Storra on the next poll."
                );
                st.execute("DROP TABLE IF EXISTS delivery_offline_queue");
                st.execute("DROP TABLE IF EXISTS delivery_history");
            }

            st.execute(
                "CREATE TABLE IF NOT EXISTS delivery_offline_queue (" +
                "  command_id TEXT PRIMARY KEY, " +
                "  player_name_lower TEXT NOT NULL, " +
                "  command TEXT NOT NULL, " +
                "  product_name TEXT, " +
                "  queued_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)" +
                ")"
            );
            st.execute(
                "CREATE INDEX IF NOT EXISTS delivery_offline_queue_player_name_idx " +
                "ON delivery_offline_queue(player_name_lower)"
            );
            st.execute(
                "CREATE TABLE IF NOT EXISTS delivery_history (" +
                "  command_id TEXT PRIMARY KEY, " +
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

    /**
     * Returns true if `table` exists AND still carries the legacy
     * `task_id` column. New schemas use `command_id`. PRAGMA
     * table_info returns zero rows for a missing table, so the call
     * also doubles as an existence check.
     */
    private static boolean hasLegacyTaskIdColumn(Connection conn, String table) throws SQLException {
        return hasLegacyColumn(conn, table, "task_id");
    }

    /**
     * Generic table_info probe — returns true if `table` exists AND
     * contains a column named `column`. Used as a schema-version
     * tripwire for one-time migrations.
     */
    private static boolean hasLegacyColumn(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT 1 FROM pragma_table_info(?) WHERE name = ?"
        )) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * True if `table` exists in the database. Lets us distinguish
     * "fresh install, no migration needed" from "old schema needs
     * dropping" — both return false for `hasLegacyColumn(new-col)`
     * but only the latter should trigger DROP+CREATE.
     */
    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?"
        )) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
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
