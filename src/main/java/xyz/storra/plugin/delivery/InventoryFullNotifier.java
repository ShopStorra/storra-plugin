package xyz.storra.plugin.delivery;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import xyz.storra.plugin.config.RemoteConfigService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders + delivers the configurable "your inventory is full" chat
 * + actionbar message to a player when a deliverable is deferred on
 * inventory-full.
 *
 * Per-player debounce: the same player can be told once per
 * DEBOUNCE_MS interval. Without this, every poll cycle (every ~30s)
 * would re-spam the message until they free space — annoying when
 * a player has a queued batch and walks away from the keyboard.
 *
 * Substitutes four tokens before color-translating:
 *   {username}     — player's name
 *   {package_name} — productName from the deferred DeliveryTask
 *   {slots_needed} — how many empty slots the player must free
 *   {server}       — server name (best-effort; Bukkit doesn't
 *                    expose this directly so we use the MOTD)
 *
 * Color codes (&c, &l, &r, etc.) translate via Bukkit's standard
 * helper. Caller passes the raw merchant string; we don't sanitize
 * because the dashboard already locks the message to printable
 * UTF-8 + the standard ampersand-color vocabulary.
 */
public final class InventoryFullNotifier {

    private static final long DEBOUNCE_MS = 30_000L;

    private final RemoteConfigService configService;
    private final Map<String, Long> lastSentAt = new ConcurrentHashMap<>();

    public InventoryFullNotifier(RemoteConfigService configService) {
        this.configService = configService;
    }

    /**
     * Send the configured inventory-full message to the player.
     * No-op if the player is null/offline or if we've already sent
     * a message in the last 30 seconds. Safe to call from any
     * thread — uses Bukkit's chat APIs which dispatch internally.
     */
    public void notify(Player player, DeliveryTask task, int slotsNeeded) {
        if (player == null || !player.isOnline()) return;
        String key = player.getUniqueId().toString();
        long now = System.currentTimeMillis();
        Long previous = lastSentAt.get(key);
        if (previous != null && now - previous < DEBOUNCE_MS) return;
        lastSentAt.put(key, now);

        String template = configService.get().inventoryFullMessage();
        if (template == null || template.isEmpty()) {
            template = RemoteConfigService.DEFAULT.inventoryFullMessage();
        }
        String raw = substituteTokens(template, player, task, slotsNeeded);
        String colored = ChatColor.translateAlternateColorCodes('&', raw);

        // Plain chat — survives chat scrollback so the player has a
        // record of what to do next. The actionbar that follows is
        // attention-grabby but ephemeral.
        player.sendMessage(colored);

        // Actionbar — pops above the hotbar for a few seconds. Works
        // off the main thread (spigot uses internal scheduling).
        try {
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(colored)
            );
        } catch (Throwable ignored) {
            // Some Paper builds gate spigot().sendMessage behind an
            // experimental flag. Plain chat already landed; not
            // worth disabling the whole notify path over.
        }
    }

    static String substituteTokens(
        String template,
        Player player,
        DeliveryTask task,
        int slotsNeeded
    ) {
        String username = player != null ? player.getName() : "";
        String packageName = task != null && task.productName() != null
            ? task.productName()
            : "your purchase";
        String serverName = safeServerName();
        return template
            .replace("{username}", username)
            .replace("{package_name}", packageName)
            .replace("{slots_needed}", String.valueOf(slotsNeeded))
            .replace("{server}", serverName);
    }

    /**
     * Best-effort server name. Bukkit doesn't expose a "server name"
     * field, so the MOTD is the closest equivalent — that's what
     * server-list listings show. Strip color codes for the
     * substitution; the configured message can reintroduce its own
     * colors around the value.
     */
    private static String safeServerName() {
        try {
            String motd = Bukkit.getMotd();
            if (motd == null || motd.isEmpty()) return "the server";
            return ChatColor.stripColor(motd);
        } catch (Throwable t) {
            return "the server";
        }
    }
}
