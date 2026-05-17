package xyz.storra.plugin.delivery;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import xyz.storra.plugin.api.StorraApiClient;

import java.util.logging.Logger;

/**
 * Orchestrates the deliver-confirm-or-fail loop for one task.
 *
 * Three paths:
 *   1. Player is online + command runs → confirm.
 *   2. requireOnline=true + player offline → enqueue in OfflineQueue;
 *      PlayerJoinListener picks it up later. No confirm or fail until
 *      the join.
 *   3. requireOnline=false + command runs (Bukkit dispatchCommand)
 *      regardless of player state → confirm.
 *
 * Bukkit.dispatchCommand MUST run on the main thread — running it
 * from PollService's async task crashes the server. We schedule a
 * one-shot Bukkit task so the command dispatch is on the main
 * thread, then call back to confirm/fail (which IS network I/O so
 * goes back to async).
 *
 * Failures (command unknown, player UUID malformed, Bukkit threw)
 * are reported via api.fail(). Network failures during the
 * confirm/fail call are swallowed + logged — the task stays in
 * Storra's pending queue + retries on the next poll cycle.
 */
public final class DeliveryManager {

    private final JavaPlugin plugin;
    private final StorraApiClient api;
    private final OfflineQueue offlineQueue;
    private final DeliveryHistory history;
    private final Logger log;

    public DeliveryManager(
        JavaPlugin plugin,
        StorraApiClient api,
        OfflineQueue offlineQueue,
        DeliveryHistory history
    ) {
        this.plugin = plugin;
        this.api = api;
        this.offlineQueue = offlineQueue;
        this.history = history;
        this.log = plugin.getLogger();
    }

    /**
     * Deliver one task. Called from PollService's async loop;
     * re-enters the main thread for Bukkit calls.
     *
     * Three paths:
     *   1. requireOnline=false: dispatch immediately as console.
     *      For broadcasts / console-only commands.
     *   2. requireOnline=true + player online: dispatch immediately,
     *      confirm to Storra.
     *   3. requireOnline=true + player offline: enqueue in local
     *      SQLite OfflineQueue. PlayerJoinListener drains on join.
     *      Storra is NOT confirmed yet — the row stays pending in
     *      delivery_queue so /pending re-emits it if the plugin
     *      restarts (RecoveryService picks it up).
     */
    public void deliver(DeliveryTask task) {
        if (!task.requireOnline()) {
            runOnMain(() -> executeAndReport(task));
            return;
        }
        String name = task.playerName();
        if (name == null || name.isEmpty()) {
            // Server contract bug: requireOnline=true with no name to
            // wait on. Don't loop forever — log + dispatch immediately.
            log.warning(
                "Task " + task.commandId() + " has requireOnline=true but no playerName; dispatching anyway"
            );
            runOnMain(() -> executeAndReport(task));
            return;
        }
        // Bukkit.getPlayerExact is sync + lock-free; checks the live
        // online-player list only. Cheap from any thread.
        if (Bukkit.getPlayerExact(name) != null) {
            runOnMain(() -> executeAndReport(task));
            return;
        }
        enqueueOffline(task);
    }

    /** Drain everything queued for a player who just joined. */
    public void deliverQueuedFor(String playerName) {
        try {
            for (OfflineQueue.QueuedTask q : offlineQueue.drainForPlayer(playerName)) {
                runOnMain(() -> {
                    boolean ok = dispatchCommand(q.command());
                    if (ok) {
                        // Fire the receipt for the queued-then-drained
                        // delivery — same UX as a live online dispatch.
                        // Reconstruct a minimal DeliveryTask for the
                        // receipt helper (it only needs productName +
                        // playerName).
                        try {
                            DeliveryTask reconstructed = DeliveryTask.forReceipt(
                                q.commandId(), playerName, q.productName()
                            );
                            DeliveryReceipt.fire(plugin, playerName, reconstructed);
                        } catch (Throwable t) {
                            log.warning("Delivery receipt failed: " + t.getMessage());
                        }
                    }
                    asyncReport(q.commandId(), ok ? null : "dispatch failed");
                    try {
                        offlineQueue.dequeue(q.commandId());
                    } catch (Exception ex) {
                        log.warning("dequeue failed for task " + q.commandId() + ": " + ex.getMessage());
                    }
                });
            }
        } catch (Exception ex) {
            log.warning("drainForPlayer failed: " + ex.getMessage());
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private void enqueueOffline(DeliveryTask task) {
        try {
            // Lowercased key so the join lookup matches regardless of
            // how the buyer cased their username at checkout.
            offlineQueue.enqueue(
                task.commandId(),
                task.playerName().toLowerCase(),
                task.command(),
                task.productName()
            );
            log.info(
                "Player " + task.playerName() + " offline; queued task " + task.commandId()
            );
            // IMPORTANT: don't confirm to Storra yet. The
            // delivery_queue row stays in 'pending' so a plugin
            // restart re-pulls it from /pending and the offline
            // queue resyncs from the source of truth. Confirmation
            // fires when the player joins and the command dispatches.
        } catch (Exception ex) {
            log.warning("Failed to enqueue offline task " + task.commandId() + ": " + ex.getMessage());
            // Surface back to Storra so it can retry later — the task
            // is at risk of being lost otherwise.
            asyncReport(task, "offline queue write failed: " + ex.getMessage());
        }
    }

    private void executeAndReport(DeliveryTask task) {
        // Inventory-full guard. Only fires when the merchant set
        // requiredSlots > 0 on the deliverable AND we have a target
        // player. Rank changes, currency grants, broadcasts (slots=0)
        // skip this entirely and dispatch unconditionally.
        if (task.requiredSlots() > 0 && task.playerName() != null) {
            Player player = Bukkit.getPlayerExact(task.playerName());
            if (player != null) {
                int free = countFreeInventorySlots(player);
                if (free < task.requiredSlots()) {
                    String reason = "inventory_full (need "
                        + task.requiredSlots() + ", have " + free + ")";
                    log.info(
                        "Deferring task " + task.commandId() + " for "
                        + task.playerName() + ": " + reason
                    );
                    try {
                        history.recordFailed(task, reason);
                    } catch (Exception ignored) {
                    }
                    asyncReport(task, reason);
                    return;
                }
            }
            // If player is null here we fall through to dispatch — the
            // requireOnline=true path already handled the offline case
            // before reaching executeAndReport, so a null here means
            // the merchant left requireOnline=false and there's no
            // player to inspect; running the command is the right call.
        }

        boolean dispatched = dispatchCommand(task.command());
        if (dispatched) {
            try {
                history.recordDelivered(task);
            } catch (Exception ignored) {
                // History is best-effort — don't fail the delivery.
            }
            // Fire the in-game receipt while we're still on the main
            // thread — chat message + sound + particles for the
            // buyer. No-op if the player went offline in the tiny
            // window between dispatch and now.
            try {
                DeliveryReceipt.fire(plugin, task.playerName(), task);
            } catch (Throwable t) {
                // Receipt is pure polish — if it throws (bad config,
                // particle/sound enum mismatch, etc.) don't let it
                // back out the delivery's confirm.
                log.warning("Delivery receipt failed: " + t.getMessage());
            }
            asyncReport(task, null);
        } else {
            try {
                history.recordFailed(task, "Bukkit dispatch returned false");
            } catch (Exception ignored) {
            }
            asyncReport(task, "Bukkit dispatch returned false");
        }
    }

    /**
     * Count empty inventory slots for a player. Looks only at the
     * 36 main storage slots (hotbar + main inventory) — armor and
     * off-hand intentionally excluded since /give targets storage.
     * Slots holding air or null both count as empty.
     */
    static int countFreeInventorySlots(Player player) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int free = 0;
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir()) {
                free++;
            }
        }
        return free;
    }

    /**
     * Run a delivery's command line. Two paths:
     *
     *   1. Broadcast — the command starts with the literal
     *      "{broadcast}" prefix. Storra-native server-wide chat
     *      message so merchants don't need a third-party broadcast
     *      plugin (Tebex requires one). The rest of the line is
     *      passed to Bukkit.broadcastMessage AFTER translating
     *      ampersand color codes (&6, &l, etc.) the way every other
     *      MC chat plugin does. Always returns true — broadcasting
     *      can't really fail except by NPE, which the outer catch
     *      handles.
     *
     *   2. Normal command — pass straight to Bukkit.dispatchCommand
     *      as the console sender. Returns Bukkit's success boolean;
     *      malformed commands throw, caught + reported.
     */
    private boolean dispatchCommand(String command) {
        try {
            if (command != null && command.startsWith(BROADCAST_PREFIX)) {
                String message = command.substring(BROADCAST_PREFIX.length());
                // Strip leading space when the merchant wrote
                // "{broadcast} message" (with a space). "{broadcast}"
                // with no space is also fine — just renders an
                // immediate-color-coded message.
                if (message.startsWith(" ")) {
                    message = message.substring(1);
                }
                String translated = org.bukkit.ChatColor.translateAlternateColorCodes('&', message);
                Bukkit.broadcastMessage(translated);
                return true;
            }
            ConsoleCommandSender console = Bukkit.getConsoleSender();
            return Bukkit.dispatchCommand(console, command);
        } catch (Throwable t) {
            log.warning("Command dispatch threw: " + t.getMessage());
            return false;
        }
    }

    private static final String BROADCAST_PREFIX = "{broadcast}";

    private void asyncReport(DeliveryTask task, String failReason) {
        asyncReport(task.commandId(), failReason);
    }

    private void asyncReport(String commandId, String failReason) {
        runAsync(() -> {
            try {
                if (failReason == null) {
                    api.confirm(commandId);
                } else {
                    api.fail(commandId, failReason);
                }
            } catch (Exception ex) {
                // Network failure — task stays in Storra's pending
                // queue and the next poll cycle re-attempts.
                log.warning(
                    "Failed to report task " + commandId + " (" +
                    (failReason == null ? "confirm" : "fail") +
                    "): " + ex.getMessage()
                );
            }
        });
    }

    private void runOnMain(Runnable task) {
        new BukkitRunnable() {
            @Override
            public void run() {
                task.run();
            }
        }.runTask(plugin);
    }

    private void runAsync(Runnable task) {
        new BukkitRunnable() {
            @Override
            public void run() {
                task.run();
            }
        }.runTaskAsynchronously(plugin);
    }
}
