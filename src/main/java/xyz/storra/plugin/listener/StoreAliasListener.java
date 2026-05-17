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
        // Tebex idiom — short lead-in line ("To visit our store, click
        // this link:") followed by the URL as its own clickable line.
        // Matches the /storra checkout output so /buy and /storra
        // checkout feel like one consistent UX.
        //
        // Adventure Component API (Paper 1.21 native) instead of
        // round-tripping JSON through `dispatchCommand("tellraw ...")`
        // — the tellraw path sometimes rendered styled but dropped
        // the clickEvent silently. Adventure hands the structured
        // Component straight to the outbound packet path.
        player.sendMessage("§7To visit our store, click this link:");
        net.kyori.adventure.text.Component link =
            net.kyori.adventure.text.Component.text(url)
                .color(net.kyori.adventure.text.format.NamedTextColor.AQUA)
                .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                    net.kyori.adventure.text.Component.text("Click to open in your browser")
                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                ));
        player.sendMessage(link);
    }

}
