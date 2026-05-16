package xyz.storra.plugin.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.storra.plugin.config.PluginConfig;

import java.util.Locale;

/**
 * Tebex-parity in-game store aliases.
 *
 * When a player types /buy or /store (configurable per-alias), we
 * cancel the command BEFORE Bukkit routes it to whichever plugin
 * happens to own that command and send the player a clickable chat
 * link to the storefront instead.
 *
 * Runs at LOW priority so a merchant who already has a /buy plugin
 * they prefer can set `aliases.intercept-buy: false` in config.yml
 * and let the other plugin take over. We never cancel commands we
 * weren't asked to handle.
 *
 * Cancellation is also no-op when `aliases.store-url` is blank —
 * with no URL to point at, falling through to whatever default
 * behavior Bukkit has (likely "Unknown command") is the better
 * outcome than a chat message that says "your store URL is unset".
 */
public final class StoreAliasListener implements Listener {

    private final JavaPlugin plugin;

    public StoreAliasListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // Plugin reads config live (not snapshotted at boot) so a
        // /storra reload after editing config.yml takes effect for
        // the next player command without bouncing services.
        xyz.storra.plugin.StorraPlugin storra =
            (xyz.storra.plugin.StorraPlugin) plugin;
        PluginConfig config = storra.getPluginConfig();
        // Config-supplied URL wins (lets merchants override per-server
        // when running multi-server setups); fall back to the URL the
        // heartbeat learned from the server. This means merchants on
        // a fresh install with a paired tenant get /buy working
        // automatically — no manual config.yml edit required.
        String url = config.storeUrl();
        if (url == null || url.isEmpty()) {
            url = storra.getRuntimeState().storeUrl();
        }
        if (url == null || url.isEmpty()) return;

        // PlayerCommandPreprocessEvent.getMessage() includes the
        // leading slash + any args. Extract just the command head
        // (lowercased) so "/Buy" and "/buy diamond_pickaxe" both
        // match the alias check.
        String msg = event.getMessage();
        if (msg == null || msg.length() < 2 || msg.charAt(0) != '/') return;
        int spaceIdx = msg.indexOf(' ');
        String head = (spaceIdx == -1 ? msg.substring(1) : msg.substring(1, spaceIdx))
            .toLowerCase(Locale.ROOT);

        boolean handle;
        switch (head) {
            case "buy":
                handle = config.interceptBuy();
                break;
            case "store":
                handle = config.interceptStore();
                break;
            default:
                return; // not our command
        }
        if (!handle) return;

        event.setCancelled(true);
        sendStorefrontLink(event.getPlayer(), url);
    }

    private void sendStorefrontLink(org.bukkit.entity.Player player, String url) {
        // tellraw JSON for clickable links — same shape the existing
        // /storra checkout + /storra sendlink commands emit, so the
        // in-game UX is consistent across all three surfaces.
        String json = "[\"\",{"
            + "\"text\":\"\\u00a7e\\u00a7l\\u272f \\u00a7r\\u00a76Visit our store: \\u00a7r\"},{"
            + "\"text\":\"\\u00a7b\\u00a7n" + escape(url) + "\","
            + "\"clickEvent\":{\"action\":\"open_url\",\"value\":\"" + escape(url) + "\"},"
            + "\"hoverEvent\":{\"action\":\"show_text\",\"value\":\"\\u00a77Click to open in your browser\"}"
            + "}]";
        try {
            org.bukkit.Bukkit.dispatchCommand(
                org.bukkit.Bukkit.getConsoleSender(),
                "tellraw " + player.getName() + " " + json
            );
        } catch (Throwable t) {
            // Fallback to plain chat if tellraw isn't accepted for
            // some reason (mod conflict, command renamed, etc.).
            player.sendMessage("§6Visit our store: §b§n" + url);
        }
    }

    /**
     * Minimal JSON string escape — URLs from config.yml shouldn't
     * contain quotes or backslashes, but we defend against a
     * misconfigured URL injecting JSON.
     */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
