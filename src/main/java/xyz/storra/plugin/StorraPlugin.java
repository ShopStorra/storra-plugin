package xyz.storra.plugin;

import org.bukkit.plugin.java.JavaPlugin;
import xyz.storra.plugin.command.StorraCommand;
import xyz.storra.plugin.config.PluginConfig;
import xyz.storra.plugin.storage.Database;

/**
 * Storra plugin lifecycle entry point.
 *
 * onEnable wires:
 *   1. Config load (defaults written to plugin data folder on first run).
 *   2. Database init (creates SQLite tables for offline queue + history).
 *   3. /storra command registration.
 *   4. Service start (poll, heartbeat, recovery, player-join listener) —
 *      iff the plugin is paired (api.server-id + api.secret are non-empty).
 *      Unpaired servers wait for the merchant to run /storra pair.
 *
 * onDisable cancels every scheduled task and closes the SQLite
 * connection so a /reload or stop leaves no leaked resources.
 *
 * Service wiring lands in ticket 7. This file ships the lifecycle
 * scaffold + command registration so `/storra status` reports the
 * pairing state from day one.
 */
public final class StorraPlugin extends JavaPlugin {

    private PluginConfig config;
    private Database database;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = PluginConfig.load(this);

        try {
            database = Database.open(this, config.databasePath(this));
        } catch (Exception ex) {
            getLogger().severe(
                "Failed to open SQLite database — disabling plugin. " + ex.getMessage()
            );
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        StorraCommand command = new StorraCommand(this);
        if (getCommand("storra") != null) {
            getCommand("storra").setExecutor(command);
            getCommand("storra").setTabCompleter(command);
        }

        if (!config.isPaired()) {
            getLogger().warning(
                "Storra plugin loaded but not paired. Run `/storra pair <code>` " +
                "from console after generating a pairing code in the dashboard."
            );
            return;
        }

        getLogger().info("Storra plugin paired. Services start in ticket 7.");
        // Services (PollService, HeartbeatService, RecoveryService,
        // PlayerJoinListener) wired in ticket 7.
    }

    @Override
    public void onDisable() {
        if (database != null) {
            try {
                database.close();
            } catch (Exception ignored) {
                // Best-effort.
            }
        }
    }

    public PluginConfig getPluginConfig() {
        return config;
    }

    public Database getDatabase() {
        return database;
    }

    /**
     * Reload from disk. Called by /storra reload after a merchant
     * edits config.yml on a running server (or after /storra pair
     * writes the pairing values).
     */
    public void reloadPluginConfig() {
        reloadConfig();
        config = PluginConfig.load(this);
    }
}
