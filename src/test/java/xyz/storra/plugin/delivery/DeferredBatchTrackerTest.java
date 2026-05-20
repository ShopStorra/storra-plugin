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
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior of DeferredBatchTracker against a temp SQLite file —
 * matches the pattern OfflineQueueTest uses (real file, not :memory:,
 * so WAL semantics are exercised).
 */
class DeferredBatchTrackerTest {

    @TempDir
    Path tempDir;

    private Connection connection;
    private Database database;
    private DeferredBatchTracker tracker;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA foreign_keys = ON");
            // Mirrors the DDL in Database.open() so the schema
            // matches what production runs against.
            st.execute(
                "CREATE TABLE deferred_batches (" +
                "  batch_key TEXT PRIMARY KEY, " +
                "  player_name TEXT, " +
                "  product_name TEXT, " +
                "  first_deferred_at INTEGER NOT NULL, " +
                "  last_deferred_at INTEGER NOT NULL, " +
                "  defer_count INTEGER NOT NULL DEFAULT 1" +
                ")"
            );
        }
        database = Database.fromConnectionForTests(connection);
        tracker = new DeferredBatchTracker(database, Logger.getLogger("test"));
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) connection.close();
    }

    @Test
    void firstDeferral_records_first_deferred_at() {
        tracker.noteDeferral("order-1::item-1", "Steve", "Diamond Rank", 1_000L);
        assertThat(tracker.firstDeferredAt("order-1::item-1")).isEqualTo(1_000L);
    }

    @Test
    void second_deferral_preserves_first_deferred_at() {
        tracker.noteDeferral("order-1::item-1", "Steve", "Diamond Rank", 1_000L);
        tracker.noteDeferral("order-1::item-1", "Steve", "Diamond Rank", 5_000L);

        // first_deferred_at must stay at the original value — the
        // timeout clock measures from the original defer event,
        // not the most recent re-defer.
        assertThat(tracker.firstDeferredAt("order-1::item-1")).isEqualTo(1_000L);
    }

    @Test
    void distinct_batches_track_independently() {
        tracker.noteDeferral("order-1::item-1", "Steve", "Diamond Rank", 1_000L);
        tracker.noteDeferral("order-2::item-1", "Alex", "VIP", 2_000L);

        assertThat(tracker.firstDeferredAt("order-1::item-1")).isEqualTo(1_000L);
        assertThat(tracker.firstDeferredAt("order-2::item-1")).isEqualTo(2_000L);
    }

    @Test
    void firstDeferredAt_returns_null_for_unknown_batch() {
        assertThat(tracker.firstDeferredAt("nope::nope")).isNull();
    }

    @Test
    void clear_removes_the_row() {
        tracker.noteDeferral("order-1::item-1", "Steve", "Diamond Rank", 1_000L);
        assertThat(tracker.firstDeferredAt("order-1::item-1")).isNotNull();

        tracker.clear("order-1::item-1");
        assertThat(tracker.firstDeferredAt("order-1::item-1")).isNull();
    }

    @Test
    void clear_is_idempotent_on_missing_row() {
        // Should not throw.
        tracker.clear("never-existed");
        assertThat(tracker.firstDeferredAt("never-existed")).isNull();
    }

    @Test
    void singleton_batch_key_tracks_like_real_batch() {
        tracker.noteDeferral("singleton::cmd-42", "Steve", "Loose item", 1_000L);
        assertThat(tracker.firstDeferredAt("singleton::cmd-42")).isEqualTo(1_000L);
    }
}
