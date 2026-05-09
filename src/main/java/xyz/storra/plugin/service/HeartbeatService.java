package xyz.storra.plugin.service;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import xyz.storra.plugin.api.StorraApiClient;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Periodic POST to /api/v1/plugin/heartbeat. Sends:
 *   - plugin_version
 *   - player_count
 *   - tps      (Paper API; first slot = 1m average)
 *   - memory_mb
 *   - cpu_percent
 *
 * Storra's dashboard game-servers page uses these values to
 * render online/offline + diagnostic cards.
 *
 * Implementation note: TPS is read via Paper's getServer().getTPS().
 * On Spigot the same call returns null and we fall back to a
 * sentinel of 20.0 (full-rate). Plugin requires Paper 1.21+ per
 * plugin.yml so the fallback is just defense-in-depth.
 */
public final class HeartbeatService {

    private final JavaPlugin plugin;
    private final StorraApiClient api;
    private final Duration interval;
    private final String pluginVersion;
    private final Logger log;
    private BukkitTask task;

    public HeartbeatService(
        JavaPlugin plugin,
        StorraApiClient api,
        Duration interval
    ) {
        this.plugin = plugin;
        this.api = api;
        this.interval = interval;
        this.pluginVersion = plugin.getPluginMeta().getVersion();
        this.log = plugin.getLogger();
    }

    public void start() {
        long ticks = Math.max(20, interval.toSeconds() * 20L);
        task = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    api.heartbeat(snapshot());
                } catch (Exception ex) {
                    log.warning("Heartbeat failed: " + ex.getMessage());
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

    private StorraApiClient.HeartbeatStats snapshot() {
        int players = Bukkit.getOnlinePlayers().size();

        double tps = 20.0;
        try {
            double[] paperTps = Bukkit.getServer().getTPS();
            if (paperTps != null && paperTps.length > 0) {
                tps = Math.min(20.0, paperTps[0]);
            }
        } catch (Throwable ignored) {
            // Spigot fallback. Plugin officially requires Paper, so
            // this branch only fires if a merchant runs against an
            // unsupported server build.
        }

        Runtime rt = Runtime.getRuntime();
        long usedBytes = rt.totalMemory() - rt.freeMemory();
        long memMb = usedBytes / (1024L * 1024L);

        double cpu = -1.0;
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                double load = sun.getCpuLoad();
                if (load >= 0) cpu = load * 100.0;
            }
        } catch (Throwable ignored) {
            // Some JVMs don't expose com.sun.management; cpu stays -1.
        }

        return new StorraApiClient.HeartbeatStats(
            pluginVersion,
            players,
            tps,
            memMb,
            cpu
        );
    }
}
