package xyz.storra.plugin.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import xyz.storra.plugin.api.StorraApiClient;

import java.util.logging.Logger;

/**
 * Polls Storra's `GET /api/v1/plugin/?action=config` endpoint for
 * plugin-side merchant config and caches the result in memory.
 *
 * Currently exposes a single value — `inventoryFullMessage` — used
 * by {@link xyz.storra.plugin.delivery.InventoryFullNotifier} to
 * tell the player when delivery defers because their inventory is
 * full. The endpoint returns a default fallback if the merchant
 * hasn't customized the message, so the plugin can ship that
 * default verbatim without baking another copy here.
 *
 * Refresh cadence: every 5 minutes. Boot fetch fires immediately so
 * the first deferred delivery already has a configured message.
 * Network failures keep the previous cached value (or DEFAULT if
 * the boot fetch failed).
 */
public final class RemoteConfigService {

    /**
     * Default that matches what Storra's server returns when the
     * merchant hasn't customized the message — kept in sync via the
     * test in this repo's `RemoteConfigServiceTest` (TODO when that
     * lands). Drifting from the server default is a bug.
     */
    public static final RemoteConfig DEFAULT = new RemoteConfig(
        "&cYour inventory is full! Free up &e{slots_needed}&c slots to receive &e{package_name}&c."
    );

    private static final long REFRESH_INTERVAL_TICKS = 20L * 60L * 5L; // 5 min

    private final JavaPlugin plugin;
    private final StorraApiClient api;
    private final Logger log;
    private volatile RemoteConfig cached = DEFAULT;
    private BukkitTask task;

    public RemoteConfigService(JavaPlugin plugin, StorraApiClient api) {
        this.plugin = plugin;
        this.api = api;
        this.log = plugin.getLogger();
    }

    public RemoteConfig get() {
        return cached;
    }

    public void start() {
        // Fire one fetch immediately so the first deferred delivery
        // already sees the merchant's configured message. Run async
        // since it's network I/O.
        new BukkitRunnable() {
            @Override
            public void run() {
                refreshOnce();
            }
        }.runTaskAsynchronously(plugin);

        task = new BukkitRunnable() {
            @Override
            public void run() {
                refreshOnce();
            }
        }.runTaskTimerAsynchronously(
            plugin,
            REFRESH_INTERVAL_TICKS,
            REFRESH_INTERVAL_TICKS
        );
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void refreshOnce() {
        try {
            StorraApiClient.RemoteConfigResult result = api.getRemoteConfig();
            if (result.ok() && result.inventoryFullMessage() != null) {
                cached = new RemoteConfig(result.inventoryFullMessage());
            }
        } catch (Exception ex) {
            // Network blip — keep last-known good. The next tick
            // retries naturally.
            log.fine("Remote-config refresh failed: " + ex.getMessage());
        }
    }

    /** Single-record snapshot of remote merchant config. */
    public record RemoteConfig(String inventoryFullMessage) {}
}
