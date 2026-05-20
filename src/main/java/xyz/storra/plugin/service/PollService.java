package xyz.storra.plugin.service;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import xyz.storra.plugin.api.StorraApiClient;
import xyz.storra.plugin.delivery.DeliveryManager;
import xyz.storra.plugin.delivery.DeliveryTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Periodic poll of Storra's `/api/v1/plugin/pending` endpoint.
 *
 * Runs async (network I/O) on a fixed cadence. Each tick:
 *   1. fetchPending() — claims up to MAX_PENDING_BATCH tasks
 *   2. dispatch each via DeliveryManager (which itself bounces
 *      between main/async threads as needed)
 *
 * Errors are swallowed + logged so a transient network blip
 * doesn't kill the loop. The next tick retries naturally.
 */
public final class PollService {

    private final JavaPlugin plugin;
    private final StorraApiClient api;
    private final DeliveryManager deliveryManager;
    private final Duration interval;
    private final Logger log;
    private BukkitTask task;

    public PollService(
        JavaPlugin plugin,
        StorraApiClient api,
        DeliveryManager deliveryManager,
        Duration interval
    ) {
        this.plugin = plugin;
        this.api = api;
        this.deliveryManager = deliveryManager;
        this.interval = interval;
        this.log = plugin.getLogger();
    }

    public void start() {
        long ticks = Math.max(20, interval.toSeconds() * 20L);
        task = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    dispatchBatches(api.fetchPending());
                } catch (Exception ex) {
                    log.warning("Poll failed: " + ex.getMessage());
                }
            }
        }.runTaskTimerAsynchronously(plugin, ticks, ticks);
    }

    /**
     * Group the response from /pending into atomic batches by
     * (orderId, orderItemId) and hand each batch to the
     * DeliveryManager. LinkedHashMap preserves dispatch order so a
     * tick that surfaced commands in the server's natural insertion
     * order delivers them the same way. Legacy server payloads
     * without orderId/orderItemId fall into per-command singletons
     * (DeliveryTask.batchKey returns "singleton::<commandId>").
     */
    private void dispatchBatches(List<DeliveryTask> tasks) {
        Map<String, List<DeliveryTask>> batches = new LinkedHashMap<>();
        for (DeliveryTask t : tasks) {
            batches.computeIfAbsent(t.batchKey(), k -> new ArrayList<>()).add(t);
        }
        for (List<DeliveryTask> batch : batches.values()) {
            deliveryManager.deliverBatch(batch);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * Trigger an out-of-band poll RIGHT NOW, in addition to the
     * normal cadence. Used by `/storra forcecheck` so an admin
     * doesn't have to wait up to 30s for the next scheduled tick.
     * Runs async exactly like the scheduled poll. Returns how many
     * tasks the API surfaced (0 if nothing was ready).
     */
    public java.util.concurrent.CompletableFuture<Integer> runNow() {
        java.util.concurrent.CompletableFuture<Integer> result =
            new java.util.concurrent.CompletableFuture<>();
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    List<DeliveryTask> tasks = api.fetchPending();
                    dispatchBatches(tasks);
                    result.complete(tasks.size());
                } catch (Exception ex) {
                    log.warning("Forced poll failed: " + ex.getMessage());
                    result.completeExceptionally(ex);
                }
            }
        }.runTaskAsynchronously(plugin);
        return result;
    }
}
