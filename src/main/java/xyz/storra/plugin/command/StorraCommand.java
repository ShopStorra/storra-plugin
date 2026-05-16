package xyz.storra.plugin.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import xyz.storra.plugin.StorraPlugin;
import xyz.storra.plugin.api.StorraApiClient;
import xyz.storra.plugin.config.PluginConfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /storra admin command.
 *
 * Subcommands:
 *   pair <code>   one-time pairing bootstrap (calls /api/v1/plugin/pair,
 *                 writes server_id + secret to config.yml)
 *   status        pairing state + last heartbeat (services not yet
 *                 wired — ticket 7 fills in the heartbeat side)
 *   history       last N deliveries (ticket 7)
 *   reload        re-read config.yml from disk
 *
 * `pair` uses StorraApiClient to hit the `/pair` endpoint. The
 * other subcommands are stubs at scaffold stage; ticket 7 wires
 * services that drive their output.
 */
public final class StorraCommand implements CommandExecutor, TabCompleter {

    private final StorraPlugin plugin;

    public StorraCommand(StorraPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String label,
        @NotNull String[] args
    ) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "pair":
                return handlePair(sender, args);
            case "status":
                return handleStatus(sender);
            case "history":
                return handleHistory(sender);
            case "reload":
                plugin.reloadPluginConfig();
                sender.sendMessage("[Storra] config reloaded.");
                return true;
            default:
                sendUsage(sender);
                return true;
        }
    }

    private boolean handlePair(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /storra pair <code>");
            return true;
        }
        String code = args[1];
        PluginConfig cfg = plugin.getPluginConfig();
        if (cfg == null) {
            sender.sendMessage("[Storra] plugin not initialized.");
            return true;
        }
        if (cfg.isPaired()) {
            sender.sendMessage(
                "[Storra] already paired (server-id=" + cfg.serverId() + "). " +
                "Generate a new code in the dashboard if you want to re-pair."
            );
            return true;
        }
        // Pairing call is network I/O — run async so we don't block
        // the server's main thread (or the console thread, depending
        // on caller). Reload + status messages get re-sent on the
        // server's main thread once the response lands.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            sender.sendMessage("[Storra] pairing…");
            try {
                StorraApiClient.PairResult result = StorraApiClient.pair(
                    cfg.baseUrl(),
                    code,
                    plugin.getLogger()
                );
                if (result == null) {
                    sender.sendMessage(
                        "[Storra] pairing failed — code is invalid or expired. " +
                        "Generate a fresh code in the dashboard."
                    );
                    return;
                }
                // Persist + reload back on the main thread (config
                // I/O is allowed off-thread but reload triggers
                // listener re-registration, which Bukkit prefers
                // on main).
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    PluginConfig.writePairing(plugin, result.serverId(), result.secret());
                    plugin.reloadPluginConfig();
                    sender.sendMessage(
                        "[Storra] paired with server-id=" + result.serverId() + ". " +
                        "Services online — polling Storra for deliveries."
                    );
                });
            } catch (Exception ex) {
                sender.sendMessage("[Storra] pairing error: " + ex.getMessage());
            }
        });
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        PluginConfig cfg = plugin.getPluginConfig();
        if (cfg == null) {
            sender.sendMessage("[Storra] plugin not initialized.");
            return true;
        }
        if (!cfg.isPaired()) {
            sender.sendMessage("[Storra] not paired. Run `/storra pair <code>`.");
            return true;
        }
        sender.sendMessage("[Storra] paired with server-id=" + cfg.serverId());
        sender.sendMessage(
            "[Storra] poll=" + cfg.pollInterval().getSeconds() + "s, " +
            "heartbeat=" + cfg.heartbeatInterval().getSeconds() + "s"
        );
        try {
            int depth = plugin.getOfflineQueue().depth();
            sender.sendMessage("[Storra] offline queue depth: " + depth);
        } catch (Exception ex) {
            sender.sendMessage("[Storra] offline queue: ?? (" + ex.getMessage() + ")");
        }
        return true;
    }

    private boolean handleHistory(CommandSender sender) {
        try {
            var rows = plugin.getDeliveryHistory().recent(25);
            if (rows.isEmpty()) {
                sender.sendMessage("[Storra] no deliveries recorded yet.");
                return true;
            }
            sender.sendMessage("[Storra] last " + rows.size() + " deliveries:");
            for (var r : rows) {
                String marker = "delivered".equals(r.status()) ? "✔" : "✖";
                String reason = r.reason() == null ? "" : " — " + r.reason();
                // command_id is a UUID — take the first 8 chars so the
                // history line stays scannable in the chat / console.
                String shortId = r.commandId() == null
                    ? "?"
                    : r.commandId().substring(0, Math.min(8, r.commandId().length()));
                sender.sendMessage(
                    "  " + marker + " #" + shortId +
                    " " + (r.playerName() == null ? "?" : r.playerName()) +
                    " · " + (r.command() == null ? "" : r.command()) +
                    reason
                );
            }
        } catch (Exception ex) {
            sender.sendMessage("[Storra] history failed: " + ex.getMessage());
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("Usage:");
        sender.sendMessage("  /storra pair <code>");
        sender.sendMessage("  /storra status");
        sender.sendMessage("  /storra history");
        sender.sendMessage("  /storra reload");
    }

    @Override
    public List<String> onTabComplete(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String alias,
        @NotNull String[] args
    ) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return Arrays.asList("pair", "status", "history", "reload")
                .stream()
                .filter(s -> s.startsWith(partial))
                .toList();
        }
        return Collections.emptyList();
    }
}
