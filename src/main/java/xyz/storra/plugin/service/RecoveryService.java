package xyz.storra.plugin.service;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import xyz.storra.plugin.api.StorraApiClient;
import xyz.storra.plugin.delivery.DeliveryManager;
import xyz.storra.plugin.delivery.DeliveryTask;

import java.util.List;
import java.util.logging.Logger;

/**
 * One-shot crash-recovery sweep run on plugin enable.
 *
 * Storra-side `/api/v1/plugin/pending` returns tasks that were
 * claimed by this server's previous run but never confirmed (the
 * plugin process died mid-delivery). Re-running them is safe
 * because Bukkit commands are idempotent at the server level — a
 * gift-card grant, a rank assignment, etc. either lands or fails
 * cleanly. The Storra-side dispatcher already protects against
 * double-fire on its end via the `processed_webhook_events` /
 * `delivery_attempts` audit; the plugin re-running a task is
 * just one more attempt that resolves to confirm or fail.
 *
 * Run async, single-shot, ~5s after enable so the server has time
 * to finish booting before we fire commands.
 */
public final class RecoveryService {

    private final JavaPlugin plugin;
    private final StorraApiClient api;
    private final DeliveryManager deliveryManager;
    private final Logger log;

    public RecoveryService(
        JavaPlugin plugin,
        StorraApiClient api,
        DeliveryManager deliveryManager
    ) {
        this.plugin = plugin;
        this.api = api;
        this.deliveryManager = deliveryManager;
        this.log = plugin.getLogger();
    }

    public void runOnce() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    List<DeliveryTask> tasks = api.fetchPending();
                    if (!tasks.isEmpty()) {
                        log.info(
                            "Recovery: re-running " + tasks.size() +
                            " unfinished tasks from previous run."
                        );
                    }
                    for (DeliveryTask t : tasks) {
                        deliveryManager.deliver(t);
                    }
                } catch (Exception ex) {
                    log.warning("Recovery sweep failed: " + ex.getMessage());
                }
            }
        }.runTaskLaterAsynchronously(plugin, 100L); // 5 seconds
    }
}
