package xyz.storra.plugin.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import xyz.storra.plugin.service.PollService;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * When a player closes any inventory (their own, a chest, an ender
 * chest, a shulker) there's a decent chance they just freed slots.
 * Trigger an immediate /pending poll so any deferred deliveries
 * waiting on inventory space get re-tried within a second instead
 * of having to wait up to the next 30s poll tick.
 *
 * Debounce: each player can trigger at most one forced poll per
 * DEBOUNCE_MS interval. A burst of inventory-management actions
 * (closing one chest after another while dumping loot) generates a
 * single re-check, not one per close.
 */
public final class InventoryCloseListener implements Listener {

    private static final long DEBOUNCE_MS = 5_000L;

    private final PollService pollService;
    private final ConcurrentMap<java.util.UUID, Long> lastTriggerAt =
        new ConcurrentHashMap<>();

    public InventoryCloseListener(PollService pollService) {
        this.pollService = pollService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (pollService == null) return;
        java.util.UUID key = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        Long previous = lastTriggerAt.get(key);
        if (previous != null && now - previous < DEBOUNCE_MS) return;
        lastTriggerAt.put(key, now);

        // runNow returns a future — we ignore it. The pending tasks
        // will dispatch through DeliveryManager which re-runs the
        // inventory-full guard on the now-fresher inventory state.
        // Failures are already logged by the service.
        try {
            pollService.runNow();
        } catch (Throwable ignored) {
            // Defensive: a service that's been stopped between the
            // listener install + the close event shouldn't break the
            // event chain.
        }
    }
}
