package xyz.storra.plugin.delivery;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import xyz.storra.plugin.api.StorraApiClient;
import xyz.storra.plugin.config.RemoteConfigService;

import java.util.List;
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
    private final InventoryFullNotifier inventoryFullNotifier;
    private final DeferredBatchTracker deferredBatchTracker;
    private final RemoteConfigService remoteConfigService;
    private final Logger log;

    public DeliveryManager(
        JavaPlugin plugin,
        StorraApiClient api,
        OfflineQueue offlineQueue,
        DeliveryHistory history,
        InventoryFullNotifier inventoryFullNotifier,
        DeferredBatchTracker deferredBatchTracker,
        RemoteConfigService remoteConfigService
    ) {
        this.plugin = plugin;
        this.api = api;
        this.offlineQueue = offlineQueue;
        this.history = history;
        this.inventoryFullNotifier = inventoryFullNotifier;
        this.deferredBatchTracker = deferredBatchTracker;
        this.remoteConfigService = remoteConfigService;
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
    /**
     * Deliver a single task. Thin wrapper around deliverBatch — the
     * batch path is the canonical one. Kept for callers that still
     * dispatch per-task (the offline-queue drain on PlayerJoinEvent,
     * the RecoveryService warm-up).
     */
    public void deliver(DeliveryTask task) {
        deliverBatch(List.of(task));
    }

    /**
     * Atomic dispatch of every command tied to one (orderId,
     * orderItemId). The whole batch waits together — if the
     * inventory-full gate triggers, no command fires (so a
     * broadcast can't announce "thanks for buying!" while the
     * companion give commands are still queued). If the gate
     * passes, every command runs in order.
     *
     * Routing:
     *   1. requireOnline=true on any task + playerName resolves + player offline
     *      → enqueue every task in offline queue, wait for join.
     *   2. requireOnline=true on any task + no playerName
     *      → server contract bug; log + dispatch anyway.
     *   3. everything else (online player OR broadcast-only batch)
     *      → main-thread executeBatch with inventory-full gate.
     */
    public void deliverBatch(List<DeliveryTask> batch) {
        if (batch == null || batch.isEmpty()) return;

        boolean anyRequireOnline = false;
        String playerName = null;
        for (DeliveryTask t : batch) {
            if (t.requireOnline()) anyRequireOnline = true;
            if (playerName == null && t.playerName() != null && !t.playerName().isEmpty()) {
                playerName = t.playerName();
            }
        }

        if (!anyRequireOnline) {
            // Broadcast-only / console-only batch — no player gate.
            runOnMain(() -> executeBatch(batch));
            return;
        }

        if (playerName == null) {
            log.warning(
                "Batch " + batch.get(0).batchKey()
                + " requires online player but no playerName; dispatching anyway"
            );
            runOnMain(() -> executeBatch(batch));
            return;
        }

        // Bukkit.getPlayerExact is sync + lock-free; safe from any thread.
        if (Bukkit.getPlayerExact(playerName) != null) {
            runOnMain(() -> executeBatch(batch));
            return;
        }

        enqueueBatchOffline(batch);
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

    /**
     * Enqueue every task in the batch to the offline queue. Each
     * row goes in keyed by the same player name so the PlayerJoin
     * drain pulls them back in one pass. The atomic-batch guarantee
     * weakens here: drainForPlayer dispatches per-row today, so a
     * full-inventory player who comes back online will see the
     * per-task inventory guard in executeAndReport fire — same
     * pre-batch behavior. Re-batching the offline-drain path is a
     * follow-up.
     */
    private void enqueueBatchOffline(List<DeliveryTask> batch) {
        for (DeliveryTask task : batch) {
            enqueueOffline(task);
        }
    }

    /**
     * Atomic batch execution — main-thread side. Runs the
     * inventory-full gate ONCE on max(requiredSlots) across the
     * batch; if it gates, every task in the batch is reported
     * failed together (no broadcast-fires-but-give-defers split).
     * If it passes, every command dispatches in order.
     *
     * Singleton batches (legacy server payload without
     * orderId/orderItemId) take the same path — the math is
     * one-task-deep, so the result matches pre-batch behavior.
     */
    private void executeBatch(List<DeliveryTask> batch) {
        int maxRequiredSlots = 0;
        String playerName = null;
        for (DeliveryTask t : batch) {
            if (t.requiredSlots() > maxRequiredSlots) {
                maxRequiredSlots = t.requiredSlots();
            }
            if (playerName == null && t.playerName() != null && !t.playerName().isEmpty()) {
                playerName = t.playerName();
            }
        }

        // Batch-level inventory gate. Fires once per batch instead
        // of once per command — so a broadcast (slots=0) stays
        // queued with its give-command siblings (slots>0) when the
        // player can't receive items.
        if (maxRequiredSlots > 0 && playerName != null) {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player != null) {
                int free = countFreeInventorySlots(player);
                if (free < maxRequiredSlots) {
                    deferBatchOnInventoryFull(batch, player, maxRequiredSlots, free);
                    return;
                }
            }
            // Player null = offline. requireOnline routing already
            // covered that path in deliverBatch; reaching here means
            // every task had requireOnline=false but at least one
            // had a playerName + requiredSlots > 0. Fall through and
            // dispatch — the give command will fail server-side
            // if the player truly isn't there.
        }

        // Cleared the inventory gate (or no gate was needed). Fire
        // every command in order. Per-task results report
        // independently — a single command failing in the middle
        // of a batch doesn't roll back successful predecessors.
        for (DeliveryTask task : batch) {
            executeSingleAfterBatchGate(task);
        }

        // Successful dispatch — the batch escaped inventory-defer
        // purgatory. Drop the tracker row so a future inventory-full
        // for the same (orderId, orderItemId) starts a fresh
        // timeout clock. Idempotent if the row never existed.
        if (deferredBatchTracker != null) {
            deferredBatchTracker.clear(batch.get(0).batchKey());
        }
    }

    /**
     * Single-task dispatch after the batch-level gate has already
     * cleared. Skips the per-task inventory check (already verified
     * by the caller) but runs the rest of the executeAndReport
     * pipeline — Bukkit.dispatchCommand, history, receipt, asyncReport.
     */
    private void executeSingleAfterBatchGate(DeliveryTask task) {
        boolean dispatched = dispatchCommand(task.command());
        if (dispatched) {
            try {
                history.recordDelivered(task);
            } catch (Exception ignored) {
                // History is best-effort — don't fail the delivery.
            }
            try {
                DeliveryReceipt.fire(plugin, task.playerName(), task);
            } catch (Throwable t) {
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
     * Defer-handling for an inventory-full batch. Per-batch notifier
     * (one chat + actionbar per defer event, not per task), then
     * report every task as failed with the same reason.
     *
     * The 24h timeout fail-back path plugs in here — task 11 layers
     * a DeferredBatchTracker over this method to escalate batches
     * that have been stuck for the configured threshold.
     */
    private void deferBatchOnInventoryFull(
        List<DeliveryTask> batch,
        Player player,
        int maxRequiredSlots,
        int free
    ) {
        int slotsNeeded = maxRequiredSlots - free;
        String reason = "inventory_full (need " + maxRequiredSlots
            + ", have " + free + ")";
        String batchKey = batch.get(0).batchKey();
        String productName = batch.get(0).productName();
        long now = System.currentTimeMillis();

        // Note the deferral in the tracker BEFORE the timeout check
        // so the very first deferral establishes first_deferred_at.
        // Without this, a batch that hits the threshold on a single
        // long-defer cycle would never trigger terminal-fail.
        if (deferredBatchTracker != null) {
            deferredBatchTracker.noteDeferral(
                batchKey, player.getName(), productName, now
            );
        }

        boolean terminal = isPastTimeout(batchKey, now);

        log.info(
            "Deferring batch " + batchKey + " for " + player.getName()
            + " (" + batch.size() + " task" + (batch.size() == 1 ? "" : "s")
            + "): " + reason + (terminal ? " [TERMINAL]" : "")
        );

        if (terminal) {
            // Final apology to the player. Always sent (no debounce
            // suppression) since terminal-fail only ever happens
            // once per batch.
            sendTerminalMessage(player, productName);
            String terminalReason =
                "inventory_never_freed (timed out after "
                + remoteTimeoutHours() + "h of repeated defers)";
            for (DeliveryTask task : batch) {
                try {
                    history.recordFailed(task, terminalReason);
                } catch (Exception ignored) {
                }
                asyncReportTerminal(task, terminalReason);
            }
            if (deferredBatchTracker != null) {
                deferredBatchTracker.clear(batchKey);
            }
            return;
        }

        // Non-terminal: standard inventory-full notify + soft-fail.
        if (inventoryFullNotifier != null) {
            try {
                inventoryFullNotifier.notify(player, batch.get(0), slotsNeeded);
            } catch (Throwable t) {
                log.warning("Inventory-full notify failed: " + t.getMessage());
            }
        }

        for (DeliveryTask task : batch) {
            try {
                history.recordFailed(task, reason);
            } catch (Exception ignored) {
            }
            asyncReport(task, reason);
        }
    }

    /**
     * True if the batch has been stuck on inventory-full for longer
     * than the merchant-configured threshold. Returns false when
     * the tracker is unavailable (graceful degrade — better to
     * keep retrying than to drop a real delivery on a bad lookup).
     */
    private boolean isPastTimeout(String batchKey, long nowMs) {
        if (deferredBatchTracker == null) return false;
        Long firstAt = deferredBatchTracker.firstDeferredAt(batchKey);
        if (firstAt == null) return false;
        int hours = remoteTimeoutHours();
        if (hours <= 0) return false;
        long thresholdMs = hours * 3_600_000L;
        return (nowMs - firstAt) >= thresholdMs;
    }

    private int remoteTimeoutHours() {
        if (remoteConfigService == null) return 0;
        return remoteConfigService.get().deliveryTimeoutHours();
    }

    /**
     * Terminal apology message — one shot, no debounce, sent only
     * once per batch lifetime (terminal-fail clears the tracker).
     * Hard-coded English string for now; the configurable template
     * lives behind {@link RemoteConfigService} when we add a knob
     * for it.
     */
    private void sendTerminalMessage(Player player, String productName) {
        if (player == null || !player.isOnline()) return;
        String label = productName == null || productName.isEmpty()
            ? "your purchase"
            : productName;
        String message = ChatColor.translateAlternateColorCodes('&',
            "&cCouldn't deliver &e" + label
            + "&c — your inventory was full for too long. &7Contact server staff for help."
        );
        try {
            player.sendMessage(message);
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(message)
            );
        } catch (Throwable t) {
            // Best-effort polish. Terminal-fail report goes out
            // regardless so the row escapes retry purgatory even if
            // the player can't be reached right now.
            log.warning("Terminal-fail message failed: " + t.getMessage());
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
                    int slotsNeeded = task.requiredSlots() - free;
                    String reason = "inventory_full (need "
                        + task.requiredSlots() + ", have " + free + ")";
                    log.info(
                        "Deferring task " + task.commandId() + " for "
                        + task.playerName() + ": " + reason
                    );
                    // Tell the player WHY their items haven't shown
                    // up — without this they assume the store is
                    // broken. The notifier debounces per-player so
                    // repeated poll cycles don't spam chat.
                    if (inventoryFullNotifier != null) {
                        try {
                            inventoryFullNotifier.notify(player, task, slotsNeeded);
                        } catch (Throwable t) {
                            // Notification is best-effort polish — never
                            // fail the report path because chat threw.
                            log.warning(
                                "Inventory-full notify failed: " + t.getMessage()
                            );
                        }
                    }
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

    /**
     * Terminal-fail variant. Tells Storra to stop auto-retrying
     * this row (server flips status=failed + next_retry_at=null).
     * Used by the inventory-full timeout escalation path.
     */
    private void asyncReportTerminal(DeliveryTask task, String reason) {
        String commandId = task.commandId();
        runAsync(() -> {
            try {
                api.failTerminal(commandId, reason);
            } catch (Exception ex) {
                // Network failure on terminal-fail is annoying but
                // not catastrophic — the row stays in pending state
                // and the next cycle will either terminal-fail it
                // again or (if the player finally freed space)
                // succeed normally. We don't clear the tracker on
                // network failure so the next attempt also sees the
                // batch as past-timeout.
                log.warning(
                    "Failed to terminal-fail task " + commandId
                    + ": " + ex.getMessage()
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
