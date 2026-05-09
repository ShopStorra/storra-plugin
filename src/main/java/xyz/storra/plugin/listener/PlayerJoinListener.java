package xyz.storra.plugin.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import xyz.storra.plugin.delivery.DeliveryManager;

/**
 * Drains the offline queue for a player who just joined.
 *
 * Fires at MONITOR priority so the player has fully spawned
 * before commands run (some delivery commands target the player
 * in-world; running pre-spawn risks a no-op).
 *
 * Drain itself is async so the SQLite read + downstream Bukkit
 * dispatch happen off the join-event thread (the dispatch
 * inside DeliveryManager bounces back to main thread for the
 * actual command execution).
 */
public final class PlayerJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final DeliveryManager deliveryManager;

    public PlayerJoinListener(JavaPlugin plugin, DeliveryManager deliveryManager) {
        this.plugin = plugin;
        this.deliveryManager = deliveryManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        new BukkitRunnable() {
            @Override
            public void run() {
                deliveryManager.deliverQueuedFor(uuid);
            }
        }.runTaskAsynchronously(plugin);
    }
}
