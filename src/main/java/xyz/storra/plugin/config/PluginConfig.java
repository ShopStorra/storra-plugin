package xyz.storra.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Typed wrapper around config.yml.
 *
 * Loaded once on enable + after /storra pair (which mutates the YAML
 * + triggers a reload). Methods return primitives instead of Bukkit
 * config nodes so the rest of the plugin doesn't have to deal with
 * config-section nullability.
 */
public final class PluginConfig {

    private final String baseUrl;
    private final String serverId;
    private final String secret;
    private final Duration pollInterval;
    private final Duration heartbeatInterval;
    private final int commandExecuteParallelism;
    private final String databaseFile;
    private final int historyRetention;
    private final boolean debug;

    private PluginConfig(
        String baseUrl,
        String serverId,
        String secret,
        Duration pollInterval,
        Duration heartbeatInterval,
        int commandExecuteParallelism,
        String databaseFile,
        int historyRetention,
        boolean debug
    ) {
        this.baseUrl = baseUrl;
        this.serverId = serverId;
        this.secret = secret;
        this.pollInterval = pollInterval;
        this.heartbeatInterval = heartbeatInterval;
        this.commandExecuteParallelism = commandExecuteParallelism;
        this.databaseFile = databaseFile;
        this.historyRetention = historyRetention;
        this.debug = debug;
    }

    public static PluginConfig load(JavaPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();
        return new PluginConfig(
            cfg.getString("api.base-url", "https://storra.xyz"),
            cfg.getString("api.server-id", ""),
            cfg.getString("api.secret", ""),
            Duration.ofSeconds(cfg.getLong("polling.poll-interval-seconds", 30)),
            Duration.ofSeconds(cfg.getLong("polling.heartbeat-interval-seconds", 60)),
            cfg.getInt("polling.command-execute-parallelism", 4),
            cfg.getString("persistence.database-file", "storra.db"),
            cfg.getInt("persistence.history-retention", 100),
            cfg.getBoolean("debug.enabled", false)
        );
    }

    /**
     * Update server-id + secret in config.yml + persist to disk.
     * Called by `/storra pair <code>` after a successful exchange.
     */
    public static void writePairing(
        JavaPlugin plugin,
        String serverId,
        String secret
    ) {
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("api.server-id", serverId);
        cfg.set("api.secret", secret);
        plugin.saveConfig();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String serverId() {
        return serverId;
    }

    public String secret() {
        return secret;
    }

    public boolean isPaired() {
        return !serverId.isEmpty() && !secret.isEmpty();
    }

    public Duration pollInterval() {
        return pollInterval;
    }

    public Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    public int commandExecuteParallelism() {
        return commandExecuteParallelism;
    }

    public Path databasePath(JavaPlugin plugin) {
        return plugin.getDataFolder().toPath().resolve(databaseFile);
    }

    public int historyRetention() {
        return historyRetention;
    }

    public boolean isDebugEnabled() {
        return debug;
    }
}
