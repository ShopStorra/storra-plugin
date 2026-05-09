package xyz.storra.plugin.delivery;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import xyz.storra.plugin.api.StorraApiClient;

import java.util.UUID;
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
     */
    public void deliver(DeliveryTask task) {
        if (task.requireOnline() && !isOnline(task.playerUuid())) {
            enqueueOffline(task);
            return;
        }
        runOnMain(() -> executeAndReport(task));
    }

    /** Drain everything queued for a player who just joined. */
    public void deliverQueuedFor(String playerUuid) {
        try {
            for (OfflineQueue.QueuedTask q : offlineQueue.drainForPlayer(playerUuid)) {
                runOnMain(() -> {
                    boolean ok = dispatchCommand(q.command());
                    asyncReport(q.taskId(), ok ? null : "dispatch failed");
                    try {
                        offlineQueue.dequeue(q.taskId());
                    } catch (Exception ex) {
                        log.warning("dequeue failed for task " + q.taskId() + ": " + ex.getMessage());
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
            offlineQueue.enqueue(task.taskId(), task.playerUuid(), task.command());
            log.info(
                "Player " + task.playerName() + " offline; queued task " + task.taskId()
            );
        } catch (Exception ex) {
            log.warning("Failed to enqueue offline task " + task.taskId() + ": " + ex.getMessage());
            // Surface back to Storra so it can retry later — the task
            // is at risk of being lost otherwise.
            asyncReport(task, "offline queue write failed: " + ex.getMessage());
        }
    }

    private void executeAndReport(DeliveryTask task) {
        boolean dispatched = dispatchCommand(task.command());
        if (dispatched) {
            try {
                history.recordDelivered(task);
            } catch (Exception ignored) {
                // History is best-effort — don't fail the delivery.
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

    /** Returns true on Bukkit success. Catches throw — Bukkit can
     *  throw on a malformed command. */
    private boolean dispatchCommand(String command) {
        try {
            ConsoleCommandSender console = Bukkit.getConsoleSender();
            return Bukkit.dispatchCommand(console, command);
        } catch (Throwable t) {
            log.warning("Command dispatch threw: " + t.getMessage());
            return false;
        }
    }

    private void asyncReport(DeliveryTask task, String failReason) {
        asyncReport(task.taskId(), failReason);
    }

    private void asyncReport(long taskId, String failReason) {
        runAsync(() -> {
            try {
                if (failReason == null) {
                    api.confirm(taskId);
                } else {
                    api.fail(taskId, failReason);
                }
            } catch (Exception ex) {
                // Network failure — task stays in Storra's pending
                // queue and the next poll cycle re-attempts.
                log.warning(
                    "Failed to report task " + taskId + " (" +
                    (failReason == null ? "confirm" : "fail") +
                    "): " + ex.getMessage()
                );
            }
        });
    }

    private boolean isOnline(String playerUuidStr) {
        try {
            UUID uuid = UUID.fromString(playerUuidStr);
            OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
            return p.isOnline();
        } catch (IllegalArgumentException ex) {
            // Malformed UUID from the server — treat as offline so
            // the task lands in the queue rather than dispatching
            // with bogus data.
            return false;
        }
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
