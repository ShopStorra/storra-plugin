package xyz.storra.plugin.delivery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.storra.plugin.storage.Database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence behavior of OfflineQueue. Uses a temp SQLite file
 * (not :memory: — we want WAL mode + on-disk semantics matching
 * production).
 *
 * Bypasses Database.open(JavaPlugin, Path) so we don't need a
 * real Bukkit server in unit tests; manual schema bootstrap +
 * direct Database wrapping reuses the same DDL.
 */
class OfflineQueueTest {

    @TempDir
    Path tempDir;

    private Connection connection;
    private Database database;
    private OfflineQueue queue;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA foreign_keys = ON");
            st.execute(
                "CREATE TABLE delivery_offline_queue (" +
                "  command_id TEXT PRIMARY KEY, " +
                "  player_name_lower TEXT NOT NULL, " +
                "  command TEXT NOT NULL, " +
                "  queued_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)" +
                ")"
            );
            st.execute(
                "CREATE INDEX delivery_offline_queue_player_name_lower_idx " +
                "ON delivery_offline_queue(player_name_lower)"
            );
        }
        database = Database.fromConnectionForTests(connection);
        queue = new OfflineQueue(database);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) connection.close();
    }

    @Test
    void enqueue_drainForPlayer_returns_rows_for_uuid() throws Exception {
        queue.enqueue("cmd-101", "uuid-a", "give Alice diamond 1");
        queue.enqueue("cmd-102", "uuid-a", "lp user Alice parent add vip");
        queue.enqueue("cmd-103", "uuid-b", "give Bob iron 5");

        List<OfflineQueue.QueuedTask> a = queue.drainForPlayer("uuid-a");
        assertThat(a).hasSize(2);
        assertThat(a).extracting(OfflineQueue.QueuedTask::commandId)
            .containsExactly("cmd-101", "cmd-102"); // ordered by queued_at ASC

        List<OfflineQueue.QueuedTask> b = queue.drainForPlayer("uuid-b");
        assertThat(b).hasSize(1);
        assertThat(b.get(0).command()).isEqualTo("give Bob iron 5");
    }

    @Test
    void drainForPlayer_returns_empty_for_unknown_uuid() throws Exception {
        queue.enqueue("cmd-101", "uuid-a", "cmd");
        assertThat(queue.drainForPlayer("never-seen")).isEmpty();
    }

    @Test
    void dequeue_removes_only_the_target_row() throws Exception {
        queue.enqueue("cmd-201", "uuid-x", "cmd-1");
        queue.enqueue("cmd-202", "uuid-x", "cmd-2");
        queue.dequeue("cmd-201");

        List<OfflineQueue.QueuedTask> remaining = queue.drainForPlayer("uuid-x");
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).commandId()).isEqualTo("cmd-202");
    }

    @Test
    void enqueue_is_idempotent_per_commandId() throws Exception {
        queue.enqueue("cmd-301", "uuid-y", "cmd-original");
        // Server re-sends the same task somehow — REPLACE keeps a
        // single row, command updated to the latest version.
        queue.enqueue("cmd-301", "uuid-y", "cmd-updated");
        List<OfflineQueue.QueuedTask> rows = queue.drainForPlayer("uuid-y");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).command()).isEqualTo("cmd-updated");
    }

    @Test
    void depth_counts_total_rows_across_players() throws Exception {
        assertThat(queue.depth()).isZero();
        queue.enqueue("cmd-401", "u1", "c1");
        queue.enqueue("cmd-402", "u2", "c2");
        queue.enqueue("cmd-403", "u1", "c3");
        assertThat(queue.depth()).isEqualTo(3);
        queue.dequeue("cmd-401");
        assertThat(queue.depth()).isEqualTo(2);
    }

    @Test
    void persists_across_database_close_reopen() throws Exception {
        queue.enqueue("cmd-501", "uuid-z", "cmd-persist");
        connection.close();

        Path dbFile = tempDir.resolve("test.db");
        Connection conn2 = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        OfflineQueue q2 = new OfflineQueue(Database.fromConnectionForTests(conn2));
        try {
            assertThat(q2.depth()).isEqualTo(1);
            assertThat(q2.drainForPlayer("uuid-z")).hasSize(1);
        } finally {
            conn2.close();
        }
    }

}
