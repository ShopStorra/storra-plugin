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
                sender.sendMessage("§aConfig reloaded.");
                return true;
            case "forcecheck":
                return handleForceCheck(sender);
            case "debug":
                return handleDebug(sender, args);
            case "checkout":
                return handleCheckout(sender, args);
            case "sendlink":
                return handleSendLink(sender, args);
            case "lookup":
                return handleLookup(sender, args);
            case "ban":
                return handleBan(sender, args);
            case "unban":
                return handleUnban(sender, args);
            case "coupon":
                return handleCoupon(sender, args);
            case "help":
            case "?":
                sendUsage(sender);
                return true;
            default:
                sendUsage(sender);
                return true;
        }
    }

    /**
     * `/storra forcecheck` — trigger an immediate `/pending` poll
     * outside the 30s cadence. Useful for testing a fresh delivery
     * or after a deploy. Reports back how many tasks landed.
     */
    private boolean handleForceCheck(CommandSender sender) {
        if (plugin.getPollService() == null) {
            sender.sendMessage("§cPoll service not running. Pair the plugin first.");
            return true;
        }
        sender.sendMessage("§ePolling for pending deliveries…");
        plugin.getPollService().runNow().whenComplete((count, err) -> {
            // whenComplete may fire on the async thread — bounce
            // back to main for the sender message (Bukkit allows
            // CommandSender.sendMessage from any thread for console,
            // but players are stricter).
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (err != null) {
                    sender.sendMessage("§cPoll failed: " + err.getMessage());
                } else if (count == 0) {
                    sender.sendMessage("§7No pending deliveries.");
                } else {
                    sender.sendMessage(
                        "§aProcessed " + count + " pending deliver"
                        + (count == 1 ? "y" : "ies") + "."
                    );
                }
            });
        });
        return true;
    }

    /**
     * `/storra debug <on|off>` — toggle verbose logging at runtime.
     * Writes to config.yml so the setting persists across plugin
     * reloads + server restarts.
     */
    private boolean handleDebug(CommandSender sender, String[] args) {
        if (args.length < 2) {
            boolean current = plugin.getConfig().getBoolean("debug.enabled", false);
            sender.sendMessage("§7Debug logging is currently " + (current ? "on" : "off") + ".");
            sender.sendMessage("§cUsage: /storra debug <on|off>");
            return true;
        }
        String value = args[1].toLowerCase();
        boolean enable;
        if (value.equals("on") || value.equals("true") || value.equals("1")) {
            enable = true;
        } else if (value.equals("off") || value.equals("false") || value.equals("0")) {
            enable = false;
        } else {
            sender.sendMessage("§cExpected 'on' or 'off'.");
            return true;
        }
        plugin.getConfig().set("debug.enabled", enable);
        plugin.saveConfig();
        plugin.reloadPluginConfig();
        sender.sendMessage("§aDebug logging " + (enable ? "enabled" : "disabled") + ".");
        return true;
    }

    private boolean handlePair(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /storra pair <code>");
            return true;
        }
        String code = args[1];
        PluginConfig cfg = plugin.getPluginConfig();
        if (cfg == null) {
            sender.sendMessage("§cPlugin not initialized.");
            return true;
        }
        if (cfg.isPaired()) {
            sender.sendMessage("§7Already paired with server " + cfg.serverId() + ".");
            sender.sendMessage("§7Generate a new code in the dashboard if you want to re-pair.");
            return true;
        }
        // Pairing call is network I/O — run async so we don't block
        // the server's main thread (or the console thread, depending
        // on caller). Reload + status messages get re-sent on the
        // server's main thread once the response lands.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            sender.sendMessage("§ePairing…");
            try {
                StorraApiClient.PairResult result = StorraApiClient.pair(
                    cfg.baseUrl(),
                    code,
                    plugin.getLogger()
                );
                if (result == null) {
                    sender.sendMessage("§cPairing failed. The code is invalid or expired.");
                    sender.sendMessage("§7Generate a fresh code in the dashboard.");
                    return;
                }
                // Persist + reload back on the main thread (config
                // I/O is allowed off-thread but reload triggers
                // listener re-registration, which Bukkit prefers
                // on main).
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    PluginConfig.writePairing(plugin, result.serverId(), result.secret());
                    plugin.reloadPluginConfig();
                    sender.sendMessage("§aPaired with server " + result.serverId() + ".");
                    sender.sendMessage("§7Services online. Polling Storra for deliveries.");
                });
            } catch (Exception ex) {
                sender.sendMessage("§cPairing error: " + ex.getMessage());
            }
        });
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        PluginConfig cfg = plugin.getPluginConfig();
        if (cfg == null) {
            sender.sendMessage("§cPlugin not initialized.");
            return true;
        }
        if (!cfg.isPaired()) {
            sender.sendMessage("§cNot paired. Run /storra pair <code>.");
            return true;
        }
        sender.sendMessage("§7Paired with server " + cfg.serverId() + ".");
        sender.sendMessage(
            "§7Poll interval: " + cfg.pollInterval().getSeconds() + "s, "
            + "heartbeat: " + cfg.heartbeatInterval().getSeconds() + "s."
        );
        try {
            int depth = plugin.getOfflineQueue().depth();
            sender.sendMessage("§7Offline queue depth: " + depth + ".");
        } catch (Exception ex) {
            sender.sendMessage("§cOffline queue unavailable: " + ex.getMessage());
        }
        return true;
    }

    private boolean handleHistory(CommandSender sender) {
        try {
            var rows = plugin.getDeliveryHistory().recent(25);
            if (rows.isEmpty()) {
                sender.sendMessage("§7No deliveries recorded yet.");
                return true;
            }
            sender.sendMessage("§7Last " + rows.size() + " deliveries:");
            for (var r : rows) {
                // Per-row status uses semantic color: green = delivered,
                // red = failed. Matches the rest of the plugin chat
                // palette so the merchant doesn't have to learn a
                // separate vocabulary for history output.
                boolean ok = "delivered".equals(r.status());
                String marker = ok ? "§a✔" : "§c✖";
                String reason = r.reason() == null ? "" : " §7— " + r.reason();
                String shortId = r.commandId() == null
                    ? "?"
                    : r.commandId().substring(0, Math.min(8, r.commandId().length()));
                sender.sendMessage(
                    marker + " §7#" + shortId
                    + " " + (r.playerName() == null ? "?" : r.playerName())
                    + " — " + (r.command() == null ? "" : r.command())
                    + reason
                );
            }
        } catch (Exception ex) {
            sender.sendMessage("§cHistory failed: " + ex.getMessage());
        }
        return true;
    }

    /**
     * `/storra checkout <packageRef>` — resolve the package and
     * send the sender a clickable payment link in chat. The
     * packageRef can be a slug, UUID, or display name; the server
     * does the lookup.
     */
    private boolean handleCheckout(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /storra checkout <package>");
            return true;
        }
        String ref = joinArgs(args, 1);
        StorraApiClient api = plugin.getApi();
        if (api == null) {
            sender.sendMessage("§cNot paired. Run /storra pair <code> first.");
            return true;
        }
        sender.sendMessage("§eResolving package…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.CheckoutUrlResult result = api.checkoutUrl(ref);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§c" + result.error());
                        return;
                    }
                    sendCheckoutLink(sender, sender, result);
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§cCheckout failed: " + ex.getMessage())
                );
            }
        });
        return true;
    }

    /**
     * `/storra sendlink <player> <packageRef>` — DM a clickable
     * payment link to another player. Useful for staff handing
     * a checkout URL to a customer mid-chat.
     */
    private boolean handleSendLink(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /storra sendlink <player> <package>");
            return true;
        }
        String targetName = args[1];
        String ref = joinArgs(args, 2);
        org.bukkit.entity.Player target = plugin.getServer().getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage("§cThat player is not online: " + targetName);
            return true;
        }
        StorraApiClient api = plugin.getApi();
        if (api == null) {
            sender.sendMessage("§cNot paired. Run /storra pair <code> first.");
            return true;
        }
        sender.sendMessage("§eResolving package…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.CheckoutUrlResult result = api.checkoutUrl(ref);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§c" + result.error());
                        return;
                    }
                    sendCheckoutLink(sender, target, result);
                    sender.sendMessage(
                        "§aSent " + result.packageName() + " link to " + target.getName() + "."
                    );
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§cSendlink failed: " + ex.getMessage())
                );
            }
        });
        return true;
    }

    /**
     * Format + dispatch the checkout link to `recipient`. Tebex
     * idiom — a short lead-in describing what they're about to
     * click, then the URL on its own line. The Minecraft client
     * auto-detects URLs in chat as clickable, so we don't need a
     * `[Click here]` tellraw wrapper that hides what we're sending.
     * The lead-in changes wording for self vs. staff-sent links so
     * the recipient knows whether they triggered it themselves.
     */
    private void sendCheckoutLink(
        CommandSender invoker,
        CommandSender recipient,
        StorraApiClient.CheckoutUrlResult result
    ) {
        String leadIn = invoker == recipient
            ? "§7To buy " + result.packageName() + ", click this link:"
            : "§7Click this link to purchase " + result.packageName() + ":";
        recipient.sendMessage(leadIn);
        // For players, send via tellraw so the URL is explicitly
        // clickable + has an open_url action (the auto-detect path
        // in modern clients still works but tellraw is more reliable
        // across mod packs). The text is the raw URL itself — no
        // `[Click here]` indirection — matching Tebex's idiom.
        if (recipient instanceof org.bukkit.entity.Player p) {
            String url = result.url();
            String escaped = url.replace("\\", "\\\\").replace("\"", "\\\"");
            String json = "[\"\",{\"text\":\"" + escaped + "\","
                + "\"color\":\"aqua\","
                + "\"underlined\":true,"
                + "\"clickEvent\":{\"action\":\"open_url\",\"value\":\"" + escaped + "\"},"
                + "\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"§7Click to open in your browser\"}"
                + "}]";
            plugin.getServer().dispatchCommand(
                plugin.getServer().getConsoleSender(),
                "tellraw " + p.getName() + " " + json
            );
        } else {
            recipient.sendMessage("§b" + result.url());
        }
    }

    /**
     * `/storra lookup <player>` — show recent transaction history
     * for a minecraft username. Tenant-scoped on the server side.
     */
    private boolean handleLookup(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /storra lookup <player>");
            return true;
        }
        String username = args[1];
        StorraApiClient api = plugin.getApi();
        if (api == null) {
            sender.sendMessage("§cNot paired. Run /storra pair <code> first.");
            return true;
        }
        sender.sendMessage("§eLooking up " + username + "…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.LookupResult result = api.lookup(username);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§c" + result.error());
                        return;
                    }
                    if (result.orders().isEmpty()) {
                        sender.sendMessage("§7No orders found for " + username + ".");
                        return;
                    }
                    sender.sendMessage(
                        "§7Recent orders for " + username
                        + " (showing " + result.orders().size() + "):"
                    );
                    for (StorraApiClient.LookupOrder o : result.orders()) {
                        // Per-row status uses semantic color: green
                        // for delivered, red for failed, yellow for
                        // anything in-between (pending, refunded).
                        // Status word inline with the line so the
                        // row stays scannable at a glance.
                        String statusColor = "delivered".equalsIgnoreCase(o.status())
                            ? "§a"
                            : "failed".equalsIgnoreCase(o.status())
                                ? "§c"
                                : "§e";
                        String items = o.items() == null || o.items().isEmpty()
                            ? "—"
                            : o.items().stream()
                                .map(i -> i.name() + (i.qty() > 1 ? " x" + i.qty() : ""))
                                .collect(java.util.stream.Collectors.joining(", "));
                        // Show amount in dollars (totalAmount is in cents).
                        double dollars = o.totalAmount() / 100.0;
                        sender.sendMessage(String.format(
                            "§7%s — %s%s§7 — $%.2f %s — %s",
                            o.transactionId(),
                            statusColor,
                            o.status() == null ? "?" : o.status(),
                            dollars,
                            o.currency() == null ? "" : o.currency(),
                            items
                        ));
                    }
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§cLookup failed: " + ex.getMessage())
                );
            }
        });
        return true;
    }

    private boolean handleBan(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /storra ban <player> [reason]");
            return true;
        }
        String username = args[1];
        String reason = args.length > 2 ? joinArgs(args, 2) : null;
        StorraApiClient api = plugin.getApi();
        if (api == null) {
            sender.sendMessage("§cNot paired. Run /storra pair <code> first.");
            return true;
        }
        sender.sendMessage("§eBanning " + username + "…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.BanResult result = api.ban(username, reason);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§cBan failed: " + result.error());
                        return;
                    }
                    String suffix = reason == null ? "" : " (" + reason + ")";
                    sender.sendMessage(
                        "§a" + result.username() + " is now banned from this store." + suffix
                    );
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§cBan failed: " + ex.getMessage())
                );
            }
        });
        return true;
    }

    private boolean handleUnban(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /storra unban <player>");
            return true;
        }
        String username = args[1];
        StorraApiClient api = plugin.getApi();
        if (api == null) {
            sender.sendMessage("§cNot paired. Run /storra pair <code> first.");
            return true;
        }
        sender.sendMessage("§eUnbanning " + username + "…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.UnbanResult result = api.unban(username);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§cUnban failed: " + result.error());
                        return;
                    }
                    if (result.wasBanned()) {
                        sender.sendMessage("§a" + result.username() + " is no longer banned.");
                    } else {
                        sender.sendMessage("§7" + result.username() + " was not on the ban list.");
                    }
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§cUnban failed: " + ex.getMessage())
                );
            }
        });
        return true;
    }

    private boolean handleCoupon(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendCouponUsage(sender);
            return true;
        }
        StorraApiClient api = plugin.getApi();
        if (api == null) {
            sender.sendMessage("§cNot paired. Run /storra pair <code> first.");
            return true;
        }
        String op = args[1].toLowerCase();
        switch (op) {
            case "list":
                return handleCouponList(sender, api);
            case "create":
                return handleCouponCreate(sender, api, args);
            case "delete":
            case "remove":
                return handleCouponDelete(sender, api, args);
            default:
                sendCouponUsage(sender);
                return true;
        }
    }

    private void sendCouponUsage(CommandSender sender) {
        sender.sendMessage("§cUsage: /storra coupon <list|create|delete>");
    }

    private boolean handleCouponList(CommandSender sender, StorraApiClient api) {
        sender.sendMessage("§eFetching coupons…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.CouponListResult result = api.couponList();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§c" + result.error());
                        return;
                    }
                    if (result.coupons().isEmpty()) {
                        sender.sendMessage("§7No coupons configured.");
                        return;
                    }
                    // Tebex idiom: header + comma-joined values on one
                    // line, no bullet rows. Each coupon renders as
                    // CODE(discount) so the row is scannable without
                    // running into chat's word-wrap.
                    String joined = result.coupons().stream()
                        .map(c -> {
                            String discount = "percentage".equals(c.discountType())
                                ? ((int) c.discountValue()) + "%"
                                : "$" + String.format(java.util.Locale.ROOT, "%.2f", c.discountValue());
                            String inactive = Boolean.FALSE.equals(c.active()) ? " (inactive)" : "";
                            return c.code() + " (" + discount + " off)" + inactive;
                        })
                        .collect(java.util.stream.Collectors.joining(", "));
                    sender.sendMessage("§7Coupons: " + joined);
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§cList failed: " + ex.getMessage())
                );
            }
        });
        return true;
    }

    private boolean handleCouponCreate(CommandSender sender, StorraApiClient api, String[] args) {
        // Args: 0=coupon, 1=create, 2=code, 3=value, 4=(optional)fixed
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /storra coupon create <code> <percent> [fixed]");
            return true;
        }
        String code = args[2];
        if (code.length() > 32) {
            sender.sendMessage("§cCode too long. Max 32 characters.");
            return true;
        }
        double value;
        try {
            value = Double.parseDouble(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cDiscount must be a number.");
            return true;
        }
        if (value <= 0) {
            sender.sendMessage("§cDiscount must be greater than zero.");
            return true;
        }
        String discountType = args.length > 4 && args[4].equalsIgnoreCase("fixed")
            ? "fixed" : "percentage";
        if ("percentage".equals(discountType) && value > 100) {
            sender.sendMessage("§cPercent must be 100 or less. Use 'fixed' for dollar amounts.");
            return true;
        }
        sender.sendMessage("§eCreating coupon " + code.toUpperCase() + "…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.CouponCreateResult result = api.couponCreate(
                    code, discountType, value, null, null
                );
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§cCreate failed: " + result.error());
                        return;
                    }
                    sender.sendMessage("§aCoupon " + result.coupon().code() + " created.");
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§cCreate failed: " + ex.getMessage())
                );
            }
        });
        return true;
    }

    private boolean handleCouponDelete(CommandSender sender, StorraApiClient api, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /storra coupon delete <code>");
            return true;
        }
        String code = args[2];
        sender.sendMessage("§eDeleting coupon " + code.toUpperCase() + "…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.CouponDeleteResult result = api.couponDelete(code);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§cDelete failed: " + result.error());
                        return;
                    }
                    if (result.deleted()) {
                        sender.sendMessage("§aCoupon " + result.code() + " deleted.");
                    } else {
                        sender.sendMessage("§7No coupon found with code " + result.code() + ".");
                    }
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§cDelete failed: " + ex.getMessage())
                );
            }
        });
        return true;
    }

    private static String joinArgs(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        String joined = sb.toString();
        // Strip surrounding ASCII double quotes so quoted tab-complete
        // suggestions (used for package names with spaces) still
        // resolve correctly against the server's exact-name match.
        if (joined.length() >= 2
            && joined.charAt(0) == '"'
            && joined.charAt(joined.length() - 1) == '"') {
            return joined.substring(1, joined.length() - 1);
        }
        return joined;
    }

    private void sendUsage(CommandSender sender) {
        // Tebex-style help: single-color (§7 gray), no bracket prefix,
        // no decorative bold or icons, no section headers. One line
        // per command — keeps the menu short and chat-quiet.
        sender.sendMessage("§7Storra admin commands:");
        sender.sendMessage("§7/storra pair <code> — pair this server to your Storra tenant");
        sender.sendMessage("§7/storra status — show pairing and heartbeat state");
        sender.sendMessage("§7/storra reload — reload config.yml");
        sender.sendMessage("§7/storra checkout <package> — generate a payment link in chat");
        sender.sendMessage("§7/storra sendlink <player> <package> — DM a payment link to a player");
        sender.sendMessage("§7/storra lookup <player> — show recent payment history for a player");
        sender.sendMessage("§7/storra ban <player> [reason] — block a player from purchasing");
        sender.sendMessage("§7/storra unban <player> — lift a purchase ban");
        sender.sendMessage("§7/storra coupon <list|create|delete> — manage discount codes");
        sender.sendMessage("§7/storra forcecheck — poll for pending deliveries now");
        sender.sendMessage("§7/storra history — show the last 25 deliveries on this server");
        sender.sendMessage("§7/storra debug <on|off> — toggle verbose logging");
        sender.sendMessage("§7/storra help — show this menu");
    }

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "pair", "status", "history", "reload",
        "forcecheck", "debug",
        "checkout", "sendlink", "lookup",
        "ban", "unban",
        "coupon",
        "help"
    );

    private static final List<String> COUPON_OPS = Arrays.asList(
        "list", "create", "delete"
    );

    @Override
    public List<String> onTabComplete(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String alias,
        @NotNull String[] args
    ) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                .filter(s -> s.startsWith(partial))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            String partial = args[1].toLowerCase();
            return Arrays.asList("on", "off").stream()
                .filter(s -> s.startsWith(partial))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("coupon")) {
            String partial = args[1].toLowerCase();
            return COUPON_OPS.stream()
                .filter(s -> s.startsWith(partial))
                .toList();
        }
        // /storra sendlink <player> + /storra lookup <player> +
        // /storra ban <player> + /storra unban <player> → suggest
        // currently-online player names.
        if (args.length == 2
            && (args[0].equalsIgnoreCase("sendlink")
                || args[0].equalsIgnoreCase("lookup")
                || args[0].equalsIgnoreCase("ban")
                || args[0].equalsIgnoreCase("unban"))) {
            String partial = args[1].toLowerCase();
            return plugin.getServer().getOnlinePlayers().stream()
                .map(org.bukkit.entity.Player::getName)
                .filter(n -> n.toLowerCase().startsWith(partial))
                .toList();
        }
        // /storra checkout <package> → suggest active package names
        // from the runtime cache. Cache populated lazily by
        // suggestPackageNames so the first tab-press kicks off the
        // async fetch and the second press (after ~ms) sees results.
        if (args.length == 2 && args[0].equalsIgnoreCase("checkout")) {
            return suggestPackageNames(args[1]);
        }
        // /storra sendlink <player> <package> — package slot is the
        // third arg here, not the second (online players occupy slot 2
        // per the block above).
        if (args.length == 3 && args[0].equalsIgnoreCase("sendlink")) {
            return suggestPackageNames(args[2]);
        }
        return Collections.emptyList();
    }

    /**
     * Pull package-name suggestions from the runtime cache. Returns
     * the cached list when fresh; on miss, kicks off an async fetch
     * and returns an empty list — the next tab-press will see results
     * once the network round-trip lands (typically <100ms). Never
     * blocks the tab-complete thread on network I/O.
     */
    private List<String> suggestPackageNames(String partial) {
        StorraApiClient api = plugin.getApi();
        if (api == null) return Collections.emptyList();
        xyz.storra.plugin.service.RuntimeState state = plugin.getRuntimeState();

        java.util.List<StorraApiClient.PackageRow> cached = state.packagesCached();
        if (cached == null) {
            // Miss — kick off the fetch and return empty so the
            // suggestion menu doesn't show stale junk while waiting.
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    StorraApiClient.PackagesListResult res = api.packagesList();
                    if (res.ok()) {
                        state.setPackages(res.packages());
                    }
                } catch (Exception ignored) {
                    // Best-effort; next tab press retries.
                }
            });
            return Collections.emptyList();
        }
        String p = partial.toLowerCase();
        return cached.stream()
            .map(StorraApiClient.PackageRow::name)
            .filter(n -> n != null && n.toLowerCase().contains(p))
            // Bukkit's tab-complete doesn't handle suggestions with
            // spaces gracefully — multi-word names get truncated at
            // the first space. Wrap them in quotes so the chat input
            // treats the whole suggestion as one token. The handler's
            // joinArgs already collapses multi-arg input back into one
            // ref, so a quoted name still resolves correctly.
            .map(n -> n.contains(" ") ? "\"" + n + "\"" : n)
            .toList();
    }
}
