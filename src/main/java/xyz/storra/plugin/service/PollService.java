package xyz.storra.plugin.service;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import xyz.storra.plugin.api.StorraApiClient;
import xyz.storra.plugin.delivery.DeliveryManager;
import xyz.storra.plugin.delivery.DeliveryTask;

import java.time.Duration;
import java.util.List;
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
                    List<DeliveryTask> tasks = api.fetchPending();
                    for (DeliveryTask t : tasks) {
                        deliveryManager.deliver(t);
                    }
                } catch (Exception ex) {
                    log.warning("Poll failed: " + ex.getMessage());
                }
            }
        }.runTaskTimerAsynchronously(plugin, ticks, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
