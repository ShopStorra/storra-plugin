package xyz.storra.plugin;

import org.bukkit.plugin.java.JavaPlugin;
import xyz.storra.plugin.api.HmacSigner;
import xyz.storra.plugin.api.StorraApiClient;
import xyz.storra.plugin.command.StorraCommand;
import xyz.storra.plugin.config.PluginConfig;
import xyz.storra.plugin.delivery.DeliveryHistory;
import xyz.storra.plugin.delivery.DeliveryManager;
import xyz.storra.plugin.delivery.OfflineQueue;
import xyz.storra.plugin.listener.PlayerJoinListener;
import xyz.storra.plugin.service.HeartbeatService;
import xyz.storra.plugin.service.PollService;
import xyz.storra.plugin.service.RecoveryService;
import xyz.storra.plugin.storage.Database;

/**
 * Storra plugin lifecycle entry point.
 *
 * onEnable wires:
 *   1. Config load (defaults written to plugin data folder on first run).
 *   2. Database init (creates SQLite tables for offline queue + history).
 *   3. /storra command registration.
 *   4. If paired, start services: PollService, HeartbeatService,
 *      RecoveryService (one-shot), PlayerJoinListener. Unpaired
 *      servers register the command but do nothing else; merchant
 *      runs /storra pair, which calls startServices() after the
 *      config rewrite.
 *
 * onDisable stops every service + closes SQLite so a /reload or
 * stop leaves no leaked resources.
 */
public final class StorraPlugin extends JavaPlugin {

    private PluginConfig config;
    private Database database;

    private PollService pollService;
    private HeartbeatService heartbeatService;
    private OfflineQueue offlineQueue;
    private DeliveryHistory history;
    private DeliveryManager deliveryManager;
    private StorraApiClient api;
    private xyz.storra.plugin.papi.StorraExpansion papiExpansion;
    private final xyz.storra.plugin.service.RuntimeState runtimeState =
        new xyz.storra.plugin.service.RuntimeState();

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

        offlineQueue = new OfflineQueue(database);
        history = new DeliveryHistory(database, config.historyRetention());

        StorraCommand command = new StorraCommand(this);
        if (getCommand("storra") != null) {
            getCommand("storra").setExecutor(command);
            getCommand("storra").setTabCompleter(command);
        }

        // /buy + /store alias interception — runs whether the plugin
        // is paired or not (it's a static chat-link reply, no API
        // dependency). Listener reads config live so /storra reload
        // picks up toggle changes immediately.
        getServer().getPluginManager().registerEvents(
            new xyz.storra.plugin.listener.StoreAliasListener(this),
            this
        );

        if (!config.isPaired()) {
            getLogger().warning(
                "Storra plugin loaded but not paired. Run `/storra pair <code>` " +
                "from console after generating a pairing code in the dashboard."
            );
            return;
        }

        startServices();
    }

    @Override
    public void onDisable() {
        stopServices();
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

    public OfflineQueue getOfflineQueue() {
        return offlineQueue;
    }

    public DeliveryHistory getDeliveryHistory() {
        return history;
    }

    /**
     * Exposes the poll service so /storra forcecheck can trigger
     * an immediate /pending pull. Returns null when the plugin
     * isn't paired (services haven't started).
     */
    public PollService getPollService() {
        return pollService;
    }

    /**
     * Exposes the HMAC-signing API client so admin commands
     * (/storra checkout, /storra sendlink, /storra lookup) can
     * call the plugin REST surface. Returns null when the plugin
     * isn't paired (services haven't started).
     */
    public StorraApiClient getApi() {
        return api;
    }

    /**
     * In-memory runtime cache shared between HeartbeatService (writes
     * storefront URL on every successful tick) + listeners + admin
     * commands (read for tab-complete + alias fallback).
     */
    public xyz.storra.plugin.service.RuntimeState getRuntimeState() {
        return runtimeState;
    }

    /**
     * Reload from disk. Called by /storra reload after a merchant
     * edits config.yml on a running server, AND by /storra pair
     * after writing the pairing values. Bounces services if the
     * pairing state changed.
     */
    public void reloadPluginConfig() {
        boolean wasPaired = config != null && config.isPaired();
        reloadConfig();
        config = PluginConfig.load(this);
        boolean nowPaired = config.isPaired();

        if (!wasPaired && nowPaired) {
            startServices();
        } else if (wasPaired && !nowPaired) {
            stopServices();
        } else if (wasPaired) {
            // Pairing unchanged but other config might have. Bounce
            // services so new poll / heartbeat intervals take effect.
            stopServices();
            startServices();
        }
    }

    /** Boot poll + heartbeat + recovery + player-join listener. */
    public void startServices() {
        if (!config.isPaired()) return;

        HmacSigner signer = new HmacSigner(config.serverId(), config.secret());
        api = new StorraApiClient(
            config.baseUrl(),
            signer,
            getLogger()
        );

        deliveryManager = new DeliveryManager(this, api, offlineQueue, history);

        pollService = new PollService(this, api, deliveryManager, config.pollInterval());
        heartbeatService = new HeartbeatService(this, api, config.heartbeatInterval());

        pollService.start();
        heartbeatService.start();
        new RecoveryService(this, api, deliveryManager).runOnce();
        getServer().getPluginManager().registerEvents(
            new PlayerJoinListener(this, deliveryManager),
            this
        );

        // PlaceholderAPI integration — opt-in soft-dep. Only register
        // when PAPI is actually installed; servers without it see no
        // change in behavior. Wrapped in try/catch so a PAPI version
        // mismatch (rare) can't take down the rest of the plugin.
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                papiExpansion = new xyz.storra.plugin.papi.StorraExpansion(this, api, "");
                if (papiExpansion.register()) {
                    getLogger().info("PlaceholderAPI integration registered (%storra_*%).");
                } else {
                    getLogger().warning(
                        "PlaceholderAPI present but expansion register returned false."
                    );
                    papiExpansion = null;
                }
            } catch (Throwable t) {
                getLogger().warning(
                    "PlaceholderAPI integration failed to load: " + t.getMessage()
                );
                papiExpansion = null;
            }
        }

        // ANSI cyan — Paper's TerminalConsoleAppender renders escape
        // codes, so the line pops in the server console alongside the
        // usual plain-white info chatter.
        getLogger().info(
            "[36mStorra plugin services online (server-id="
                + config.serverId() + ").[0m"
        );
    }

    /** Cancel scheduled services. Listener is unregistered via
     *  HandlerList on plugin disable; an explicit re-register on
     *  startServices is sufficient for the reload path. */
    public void stopServices() {
        if (pollService != null) {
            pollService.stop();
            pollService = null;
        }
        if (heartbeatService != null) {
            heartbeatService.stop();
            heartbeatService = null;
        }
        // Cached runtime state belongs to a single (tenant, server-id)
        // pairing. A reload that re-pairs to a different tenant must
        // start fresh — otherwise the previous tenant's storefront URL
        // would leak into the new pairing's /buy chat link.
        runtimeState.clear();
        if (papiExpansion != null) {
            try {
                papiExpansion.unregister();
            } catch (Throwable ignored) {
                // PAPI may have already cleaned up on its own disable.
            }
            papiExpansion = null;
        }
        org.bukkit.event.HandlerList.unregisterAll(this);
    }
}
