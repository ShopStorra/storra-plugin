package xyz.storra.plugin.delivery;

import xyz.storra.plugin.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * SQLite-backed memory of which batches have been deferred on
 * inventory-full and for how long. The timeout fail-back path
 * reads `first_deferred_at` to decide whether to escalate a stuck
 * batch to terminal-fail.
 *
 * Keyed by `DeliveryTask.batchKey()` — "orderId::orderItemId" for
 * real batches, "singleton::commandId" for legacy server payloads
 * without orderId/orderItemId. Singletons get the same treatment;
 * the math collapses to per-task.
 *
 * All writes are upsert-style — re-deferring an already-tracked
 * batch only updates `last_deferred_at` + bumps `defer_count`,
 * preserving the original `first_deferred_at`.
 *
 * SQLite errors degrade gracefully: read failures return null
 * (caller treats as "no record"), write failures log + continue.
 * The timeout feature is a safety net; a corrupted local DB
 * shouldn't drop deliveries.
 */
public final class DeferredBatchTracker {

    private final Database database;
    private final Logger log;

    public DeferredBatchTracker(Database database, Logger log) {
        this.database = database;
        this.log = log;
    }

    /**
     * Record that a batch deferred this tick. New rows get
     * `first_deferred_at = last_deferred_at = now`. Subsequent
     * calls bump `last_deferred_at` + counter; `first_deferred_at`
     * stays put so the timeout clock measures from the original
     * defer event.
     */
    public void noteDeferral(
        String batchKey,
        String playerName,
        String productName,
        long nowMs
    ) {
        try (PreparedStatement ps = database.connection().prepareStatement(
            "INSERT INTO deferred_batches " +
            "  (batch_key, player_name, product_name, first_deferred_at, last_deferred_at, defer_count) " +
            "VALUES (?, ?, ?, ?, ?, 1) " +
            "ON CONFLICT(batch_key) DO UPDATE SET " +
            "  last_deferred_at = excluded.last_deferred_at, " +
            "  defer_count = deferred_batches.defer_count + 1"
        )) {
            ps.setString(1, batchKey);
            ps.setString(2, playerName);
            ps.setString(3, productName);
            ps.setLong(4, nowMs);
            ps.setLong(5, nowMs);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.warning(
                "DeferredBatchTracker.noteDeferral failed for "
                + batchKey + ": " + ex.getMessage()
            );
        }
    }

    /**
     * Returns the recorded `first_deferred_at` for this batch, or
     * null if no row exists (e.g. never deferred, or just cleared
     * after a successful dispatch).
     */
    public Long firstDeferredAt(String batchKey) {
        try (PreparedStatement ps = database.connection().prepareStatement(
            "SELECT first_deferred_at FROM deferred_batches WHERE batch_key = ?"
        )) {
            ps.setString(1, batchKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
                return null;
            }
        } catch (SQLException ex) {
            log.warning(
                "DeferredBatchTracker.firstDeferredAt failed for "
                + batchKey + ": " + ex.getMessage()
            );
            return null;
        }
    }

    /**
     * Clear the tracker entry for a batch — call after the batch
     * successfully dispatches OR after a terminal-fail is reported
     * to Storra. Idempotent: deletes 0 rows if no entry exists.
     */
    public void clear(String batchKey) {
        try (PreparedStatement ps = database.connection().prepareStatement(
            "DELETE FROM deferred_batches WHERE batch_key = ?"
        )) {
            ps.setString(1, batchKey);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.warning(
                "DeferredBatchTracker.clear failed for "
                + batchKey + ": " + ex.getMessage()
            );
        }
    }
}
