package xyz.storra.plugin.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import xyz.storra.plugin.StorraPlugin;
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
                sender.sendMessage("[Storra] history will land in ticket 7.");
                return true;
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
        sender.sendMessage("[Storra] /pair logic lands in ticket 6 (StorraApiClient). Got code: "
            + code.substring(0, Math.min(4, code.length())) + "…");
        // Ticket 6: call StorraApiClient.pair(code), receive
        // { server_id, secret }, PluginConfig.writePairing(...),
        // plugin.reloadPluginConfig(), start services.
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
        sender.sendMessage("[Storra] poll-interval=" + cfg.pollInterval().getSeconds() + "s, "
            + "heartbeat-interval=" + cfg.heartbeatInterval().getSeconds() + "s");
        sender.sendMessage("[Storra] services are not running yet (ticket 7).");
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
