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
 *   - version          (plugin version)
 *   - playerCount      (Bukkit.getOnlinePlayers().size())
 *   - maxPlayers       (Bukkit.getMaxPlayers())
 *   - tps              (Paper getServer().getTPS()[0] — 1m average)
 *   - mspt             (Paper getServer().getAverageTickTime() — ms)
 *   - memoryUsedMb     (Runtime — heap used)
 *   - memoryMaxMb      (Runtime — heap cap)
 *   - cpuPercent       (com.sun.management OS bean, 0-100)
 *   - entityCount      (sum across all loaded worlds)
 *   - chunkCount       (sum across all loaded worlds)
 *
 * Field names are camelCase to match the server's handleHeartbeat
 * contract. v1 was snake_case and silently dropped everything
 * except `tps` which happened to match by accident — hence the
 * dashboard showing "—" for every stat post-retrofit.
 *
 * Implementation note: TPS / MSPT are read via Paper API. On
 * Spigot the same calls return null/throw and we fall back to
 * sentinels (20.0 / 0.0). Plugin requires Paper 1.21+ per
 * plugin.yml so the fallback is defense-in-depth.
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
        // Three-stage scheduling per cycle:
        //   1. Async timer fires (we're now on an async thread)
        //   2. Hop to main thread to read world state (entities,
        //      loaded chunks) — Bukkit's AsyncCatcher trips otherwise
        //      and World.getEntities() throws on Paper/Purpur
        //   3. Hop back to async to compose the stats payload and
        //      run the HTTP POST
        task = new BukkitRunnable() {
            @Override
            public void run() {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    int entities = 0;
                    int chunks = 0;
                    try {
                        for (org.bukkit.World w : Bukkit.getWorlds()) {
                            entities += w.getEntities().size();
                            chunks += w.getLoadedChunks().length;
                        }
                    } catch (Throwable t) {
                        // Best-effort — counts default to 0 if world
                        // iteration trips somehow. Heartbeat still
                        // fires with the other fields populated.
                    }
                    final int finalEntities = entities;
                    final int finalChunks = chunks;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            api.heartbeat(snapshot(finalEntities, finalChunks));
                        } catch (Exception ex) {
                            log.warning("Heartbeat failed: " + ex.getMessage());
                        }
                    });
                });
            }
        }.runTaskTimerAsynchronously(plugin, ticks, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * Compose the heartbeat payload. Entity + chunk counts are
     * passed in by the caller because they must be read on the
     * main thread; everything else here is async-safe (online-
     * player count, server TPS/MSPT, JVM memory + CPU).
     */
    private StorraApiClient.HeartbeatStats snapshot(int entities, int chunks) {
        int players = Bukkit.getOnlinePlayers().size();
        int maxPlayers = Bukkit.getMaxPlayers();

        double tps = 20.0;
        double mspt = 0.0;
        try {
            double[] paperTps = Bukkit.getServer().getTPS();
            if (paperTps != null && paperTps.length > 0) {
                tps = Math.min(20.0, paperTps[0]);
            }
            mspt = Bukkit.getServer().getAverageTickTime();
        } catch (Throwable ignored) {
            // Spigot fallback. Plugin officially requires Paper, so
            // this branch only fires if a merchant runs against an
            // unsupported server build.
        }

        Runtime rt = Runtime.getRuntime();
        long usedBytes = rt.totalMemory() - rt.freeMemory();
        long memUsedMb = usedBytes / (1024L * 1024L);
        // maxMemory may return Long.MAX_VALUE if the JVM has no cap;
        // treat that as "unknown" via 0 so the dashboard shows —.
        long maxBytes = rt.maxMemory();
        long memMaxMb = maxBytes == Long.MAX_VALUE ? 0L : maxBytes / (1024L * 1024L);

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
            maxPlayers,
            tps,
            mspt,
            memUsedMb,
            memMaxMb,
            cpu,
            entities,
            chunks
        );
    }
}
