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
            sender.sendMessage("[Storra] poll service not running — pair the plugin first.");
            return true;
        }
        sender.sendMessage("[Storra] polling now…");
        plugin.getPollService().runNow().whenComplete((count, err) -> {
            // whenComplete may fire on the async thread — bounce
            // back to main for the sender message (Bukkit allows
            // CommandSender.sendMessage from any thread for console,
            // but players are stricter).
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (err != null) {
                    sender.sendMessage("[Storra] poll failed: " + err.getMessage());
                } else if (count == 0) {
                    sender.sendMessage("[Storra] no pending deliveries.");
                } else {
                    sender.sendMessage(
                        "[Storra] processed " + count + " pending deliver"
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
            sender.sendMessage(
                "[Storra] debug is currently " + (current ? "ON" : "OFF")
                + " — usage: /storra debug <on|off>"
            );
            return true;
        }
        String value = args[1].toLowerCase();
        boolean enable;
        if (value.equals("on") || value.equals("true") || value.equals("1")) {
            enable = true;
        } else if (value.equals("off") || value.equals("false") || value.equals("0")) {
            enable = false;
        } else {
            sender.sendMessage("[Storra] expected 'on' or 'off', got: " + args[1]);
            return true;
        }
        plugin.getConfig().set("debug.enabled", enable);
        plugin.saveConfig();
        plugin.reloadPluginConfig();
        sender.sendMessage("[Storra] debug logging " + (enable ? "ENABLED" : "DISABLED") + ".");
        return true;
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

    /**
     * `/storra checkout <packageRef>` — resolve the package and
     * send the sender a clickable payment link in chat. The
     * packageRef can be a slug, UUID, or display name; the server
     * does the lookup.
     */
    private boolean handleCheckout(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§7Usage: §f/storra checkout <package>");
            return true;
        }
        String ref = joinArgs(args, 1);
        StorraApiClient api = plugin.getApi();
        if (api == null) {
            sender.sendMessage("§c[Storra] not paired — run /storra pair <code> first.");
            return true;
        }
        sender.sendMessage("§7[Storra] resolving package…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.CheckoutUrlResult result = api.checkoutUrl(ref);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§c[Storra] " + result.error());
                        return;
                    }
                    sendCheckoutLink(sender, sender, result);
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§c[Storra] checkout failed: " + ex.getMessage())
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
            sender.sendMessage("§7Usage: §f/storra sendlink <player> <package>");
            return true;
        }
        String targetName = args[1];
        String ref = joinArgs(args, 2);
        org.bukkit.entity.Player target = plugin.getServer().getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage("§c[Storra] player not online: " + targetName);
            return true;
        }
        StorraApiClient api = plugin.getApi();
        if (api == null) {
            sender.sendMessage("§c[Storra] not paired — run /storra pair <code> first.");
            return true;
        }
        sender.sendMessage("§7[Storra] resolving package…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.CheckoutUrlResult result = api.checkoutUrl(ref);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§c[Storra] " + result.error());
                        return;
                    }
                    sendCheckoutLink(sender, target, result);
                    sender.sendMessage(
                        "§a[Storra] sent " + result.packageName() + " link to " + target.getName() + "."
                    );
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§c[Storra] sendlink failed: " + ex.getMessage())
                );
            }
        });
        return true;
    }

    /**
     * Format + dispatch the checkout link to `recipient`. Uses
     * Bukkit's tellraw JSON for a clickable URL — much nicer UX
     * than a copy-paste plaintext link.
     */
    private void sendCheckoutLink(
        CommandSender invoker,
        CommandSender recipient,
        StorraApiClient.CheckoutUrlResult result
    ) {
        recipient.sendMessage("§6§l✦ " + (invoker == recipient ? "Checkout" : "Purchase invite") + " §6§l✦");
        recipient.sendMessage("§7Package: §f" + result.packageName());
        recipient.sendMessage("§7Store:   §f" + result.storeName());
        // For console / non-player recipients, just show the URL
        // (no tellraw click event). For players, use tellraw JSON.
        if (recipient instanceof org.bukkit.entity.Player p) {
            String json = "[\"\",{\"text\":\"§a[Click here to open the checkout]\","
                + "\"clickEvent\":{\"action\":\"open_url\",\"value\":\""
                + result.url().replace("\"", "\\\"") + "\"},"
                + "\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\""
                + result.url().replace("\"", "\\\"") + "\"}}]";
            plugin.getServer().dispatchCommand(
                plugin.getServer().getConsoleSender(),
                "tellraw " + p.getName() + " " + json
            );
        } else {
            recipient.sendMessage("§7URL: §f" + result.url());
        }
    }

    /**
     * `/storra lookup <player>` — show recent transaction history
     * for a minecraft username. Tenant-scoped on the server side.
     */
    private boolean handleLookup(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§7Usage: §f/storra lookup <player>");
            return true;
        }
        String username = args[1];
        StorraApiClient api = plugin.getApi();
        if (api == null) {
            sender.sendMessage("§c[Storra] not paired — run /storra pair <code> first.");
            return true;
        }
        sender.sendMessage("§7[Storra] looking up " + username + "…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.LookupResult result = api.lookup(username);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§c[Storra] " + result.error());
                        return;
                    }
                    if (result.orders().isEmpty()) {
                        sender.sendMessage("§7[Storra] no orders found for §f" + username + "§7.");
                        return;
                    }
                    sender.sendMessage("§6§lOrders for " + username + " §7(last " + result.orders().size() + ")");
                    for (StorraApiClient.LookupOrder o : result.orders()) {
                        String statusColor = "delivered".equalsIgnoreCase(o.status())
                            ? "§a"
                            : "failed".equalsIgnoreCase(o.status())
                                ? "§c"
                                : "§e";
                        String items = o.items() == null || o.items().isEmpty()
                            ? "—"
                            : o.items().stream()
                                .map(i -> i.name() + (i.qty() > 1 ? " ×" + i.qty() : ""))
                                .collect(java.util.stream.Collectors.joining(", "));
                        // Show amount in dollars (totalAmount is in cents).
                        double dollars = o.totalAmount() / 100.0;
                        sender.sendMessage(String.format(
                            "§7  %s §8· %s$%.2f §7%s §8· %s",
                            o.transactionId(),
                            statusColor,
                            dollars,
                            o.currency() == null ? "" : o.currency(),
                            items
                        ));
                    }
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§c[Storra] lookup failed: " + ex.getMessage())
                );
            }
        });
        return true;
    }

    private boolean handleBan(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§7Usage: §f/storra ban <player> [reason]");
            return true;
        }
        String username = args[1];
        String reason = args.length > 2 ? joinArgs(args, 2) : null;
        StorraApiClient api = plugin.getApi();
        if (api == null) {
            sender.sendMessage("§c[Storra] not paired — run /storra pair <code> first.");
            return true;
        }
        sender.sendMessage("§7[Storra] banning §f" + username + "§7…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.BanResult result = api.ban(username, reason);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§c[Storra] ban failed: " + result.error());
                        return;
                    }
                    String suffix = reason == null ? "" : " §7(" + reason + ")";
                    sender.sendMessage("§a[Storra] §f" + result.username()
                        + " §ais now banned from this store." + suffix);
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§c[Storra] ban failed: " + ex.getMessage())
                );
            }
        });
        return true;
    }

    private boolean handleUnban(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§7Usage: §f/storra unban <player>");
            return true;
        }
        String username = args[1];
        StorraApiClient api = plugin.getApi();
        if (api == null) {
            sender.sendMessage("§c[Storra] not paired — run /storra pair <code> first.");
            return true;
        }
        sender.sendMessage("§7[Storra] unbanning §f" + username + "§7…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.UnbanResult result = api.unban(username);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§c[Storra] unban failed: " + result.error());
                        return;
                    }
                    if (result.wasBanned()) {
                        sender.sendMessage("§a[Storra] §f" + result.username()
                            + " §ais no longer banned.");
                    } else {
                        sender.sendMessage("§7[Storra] §f" + result.username()
                            + " §7was not on the ban list.");
                    }
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§c[Storra] unban failed: " + ex.getMessage())
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
            sender.sendMessage("§c[Storra] not paired — run /storra pair <code> first.");
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
        sender.sendMessage("§7Usage:");
        sender.sendMessage("§7   §f/storra coupon list");
        sender.sendMessage("§7   §f/storra coupon create <code> <percent> §7(e.g. SUMMER 25)");
        sender.sendMessage("§7   §f/storra coupon create <code> <amount> fixed §7(dollars off)");
        sender.sendMessage("§7   §f/storra coupon delete <code>");
    }

    private boolean handleCouponList(CommandSender sender, StorraApiClient api) {
        sender.sendMessage("§7[Storra] fetching coupons…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.CouponListResult result = api.couponList();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§c[Storra] " + result.error());
                        return;
                    }
                    if (result.coupons().isEmpty()) {
                        sender.sendMessage("§7[Storra] no coupons configured.");
                        return;
                    }
                    sender.sendMessage("§6§lActive coupons §7(" + result.coupons().size() + ")");
                    for (StorraApiClient.CouponRow c : result.coupons()) {
                        String discount = "percentage".equals(c.discountType())
                            ? ((int) c.discountValue()) + "% off"
                            : "$" + String.format(java.util.Locale.ROOT, "%.2f", c.discountValue()) + " off";
                        String uses = c.maxUses() == null
                            ? (c.currentUses() == null ? "" : "§7 — " + c.currentUses() + " uses")
                            : "§7 — " + c.currentUses() + "/" + c.maxUses() + " uses";
                        String status = Boolean.FALSE.equals(c.active()) ? " §c[inactive]" : "";
                        sender.sendMessage("§7  §e" + c.code() + " §f" + discount + uses + status);
                    }
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§c[Storra] list failed: " + ex.getMessage())
                );
            }
        });
        return true;
    }

    private boolean handleCouponCreate(CommandSender sender, StorraApiClient api, String[] args) {
        // Args: 0=coupon, 1=create, 2=code, 3=value, 4=(optional)fixed
        if (args.length < 4) {
            sender.sendMessage("§7Usage: §f/storra coupon create <code> <percent> §7(or <amount> fixed)");
            return true;
        }
        String code = args[2];
        if (code.length() > 32) {
            sender.sendMessage("§c[Storra] code too long (max 32 chars).");
            return true;
        }
        double value;
        try {
            value = Double.parseDouble(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§c[Storra] discount must be a number.");
            return true;
        }
        if (value <= 0) {
            sender.sendMessage("§c[Storra] discount must be > 0.");
            return true;
        }
        String discountType = args.length > 4 && args[4].equalsIgnoreCase("fixed")
            ? "fixed" : "percentage";
        if ("percentage".equals(discountType) && value > 100) {
            sender.sendMessage("§c[Storra] percent must be ≤100. Use 'fixed' for dollar-amount discounts.");
            return true;
        }
        sender.sendMessage("§7[Storra] creating coupon §f" + code.toUpperCase() + "§7…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.CouponCreateResult result = api.couponCreate(
                    code, discountType, value, null, null
                );
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§c[Storra] create failed: " + result.error());
                        return;
                    }
                    String discount = "percentage".equals(result.coupon().discountType())
                        ? ((int) result.coupon().discountValue()) + "% off"
                        : "$" + String.format(java.util.Locale.ROOT, "%.2f", result.coupon().discountValue()) + " off";
                    sender.sendMessage("§a[Storra] coupon §f" + result.coupon().code()
                        + " §acreated — " + discount);
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§c[Storra] create failed: " + ex.getMessage())
                );
            }
        });
        return true;
    }

    private boolean handleCouponDelete(CommandSender sender, StorraApiClient api, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§7Usage: §f/storra coupon delete <code>");
            return true;
        }
        String code = args[2];
        sender.sendMessage("§7[Storra] deleting coupon §f" + code.toUpperCase() + "§7…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorraApiClient.CouponDeleteResult result = api.couponDelete(code);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!result.ok()) {
                        sender.sendMessage("§c[Storra] delete failed: " + result.error());
                        return;
                    }
                    if (result.deleted()) {
                        sender.sendMessage("§a[Storra] coupon §f" + result.code() + " §adeleted.");
                    } else {
                        sender.sendMessage("§7[Storra] no coupon found with code §f" + result.code() + "§7.");
                    }
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§c[Storra] delete failed: " + ex.getMessage())
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
        sender.sendMessage("§6§lStorra Plugin§r §7— admin commands:");
        sender.sendMessage("");
        sender.sendMessage("§e Setup");
        sender.sendMessage("§7   /storra pair §f<code>§7              pair to your Storra tenant");
        sender.sendMessage("§7   /storra status                       show pairing + heartbeat state");
        sender.sendMessage("§7   /storra reload                       reload config.yml");
        sender.sendMessage("");
        sender.sendMessage("§e Store");
        sender.sendMessage("§7   /storra checkout §f<package>§7       generate a payment link in chat");
        sender.sendMessage("§7   /storra sendlink §f<player> <pkg>§7  DM a payment link to a player");
        sender.sendMessage("§7   /storra lookup §f<player>§7          payment history for a player");
        sender.sendMessage("");
        sender.sendMessage("§e Moderation");
        sender.sendMessage("§7   /storra ban §f<player> [reason]§7    block a player from purchasing");
        sender.sendMessage("§7   /storra unban §f<player>§7           lift a purchase ban");
        sender.sendMessage("");
        sender.sendMessage("§e Promotions");
        sender.sendMessage("§7   /storra coupon list                  show active coupons");
        sender.sendMessage("§7   /storra coupon create §f<code> <%>§7  create a percent-off code");
        sender.sendMessage("§7   /storra coupon delete §f<code>§7      remove a coupon code");
        sender.sendMessage("");
        sender.sendMessage("§e Operations");
        sender.sendMessage("§7   /storra forcecheck                   poll for pending deliveries now");
        sender.sendMessage("§7   /storra history                      last N deliveries on this server");
        sender.sendMessage("§7   /storra debug §f<on|off>§7           toggle verbose logging");
        sender.sendMessage("");
        sender.sendMessage("§7   /storra help                         show this menu");
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
