package xyz.storra.plugin.delivery;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * The "your purchase landed" moment — fires after a delivery
 * command successfully dispatches AND the target player is online
 * to see it. Wraps the otherwise-silent dispatch with a chat
 * receipt, an optional title flash, a sound, and a particle burst.
 *
 * Tebex's plugin leaves this entirely to the merchant — they have
 * to bolt on a `tellraw` command for every package, and most
 * don't. Storra makes it the default: a buyer who pays real money
 * SHOULD feel something concrete happen in-game beyond the item
 * appearing in their inventory.
 *
 * All four channels (chat / title / sound / particle) are
 * independently configurable in config.yml — clear any value to
 * mute that channel, or set receipt.enabled=false to silence
 * everything. Bad Bukkit enum names (mismatched MC version) skip
 * the offending channel silently rather than crash the receipt.
 *
 * MUST be called from the main thread — Bukkit Player operations
 * (sendMessage, sendTitle, playSound, spawnParticle) are not
 * thread-safe.
 */
public final class DeliveryReceipt {

    private DeliveryReceipt() {}

    /**
     * Fire the receipt for a just-completed delivery. Looks up the
     * target player by name; no-op if they went offline between
     * dispatch and receipt (race window is tiny but possible).
     */
    public static void fire(JavaPlugin plugin, String playerName, DeliveryTask task) {
        if (playerName == null || playerName.isEmpty()) return;
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) return;

        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("receipt.enabled", true)) return;

        String packageName = task.productName() != null && !task.productName().isEmpty()
            ? task.productName()
            : "your purchase";

        Map<String, String> vars = Map.of(
            "{package}", packageName,
            "{player}", playerName
        );

        sendChat(player, cfg, vars);
        sendTitle(player, cfg, vars);
        playSound(player, cfg);
        spawnParticles(player, cfg);
    }

    private static void sendChat(Player player, FileConfiguration cfg, Map<String, String> vars) {
        String tpl = cfg.getString("receipt.chat", "");
        if (tpl == null || tpl.isEmpty()) return;
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', applyVars(tpl, vars)));
    }

    private static void sendTitle(Player player, FileConfiguration cfg, Map<String, String> vars) {
        String titleTpl = cfg.getString("receipt.title", "");
        String subtitleTpl = cfg.getString("receipt.subtitle", "");
        boolean hasTitle = titleTpl != null && !titleTpl.isEmpty();
        boolean hasSubtitle = subtitleTpl != null && !subtitleTpl.isEmpty();
        if (!hasTitle && !hasSubtitle) return;
        String title = hasTitle ? ChatColor.translateAlternateColorCodes('&', applyVars(titleTpl, vars)) : "";
        String subtitle = hasSubtitle ? ChatColor.translateAlternateColorCodes('&', applyVars(subtitleTpl, vars)) : "";
        // 10 ticks fade-in (0.5s), 60 ticks stay (3s), 20 ticks fade-out (1s)
        player.sendTitle(title, subtitle, 10, 60, 20);
    }

    private static void playSound(Player player, FileConfiguration cfg) {
        String name = cfg.getString("receipt.sound", "");
        if (name == null || name.isEmpty()) return;
        try {
            Sound sound = Sound.valueOf(name.toUpperCase());
            // Volume 1.0, pitch 1.0 — neutral. Pitch could be exposed
            // later if merchants want to tune the receipt vibe.
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException ignored) {
            // Bad enum name (likely a typo or a Sound from a newer
            // MC version than this server runs). Silent skip — log
            // would just create noise on every delivery.
        }
    }

    private static void spawnParticles(Player player, FileConfiguration cfg) {
        String name = cfg.getString("receipt.particle", "");
        int count = cfg.getInt("receipt.particle-count", 0);
        if (name == null || name.isEmpty() || count <= 0) return;
        try {
            Particle particle = Particle.valueOf(name.toUpperCase());
            // Spread the particles in a small 1m³ box around the
            // player — TOTEM_OF_UNDYING in particular looks great
            // with this kind of dispersion.
            player.getWorld().spawnParticle(
                particle,
                player.getLocation(),
                count,
                0.5, 1.0, 0.5,
                0.0
            );
        } catch (IllegalArgumentException ignored) {
            // Same as sound — bad enum name, silent skip.
        }
    }

    private static String applyVars(String tpl, Map<String, String> vars) {
        String result = tpl;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            result = result.replace(e.getKey(), e.getValue());
        }
        return result;
    }
}
