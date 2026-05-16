package xyz.storra.plugin.delivery;

import com.google.gson.annotations.SerializedName;

/**
 * One unit of work pulled from `/api/v1/plugin/pending`. Mirrors
 * the JSON shape Storra's `delivery_queue` row serializes to.
 *
 * v2 wire contract (matches `src/routes/api/v1/plugin/$.ts`):
 *   {
 *     "id":            "<uuid>",
 *     "command":       "<bukkit command string>",
 *     "playerName":    "<in-game name>"  | null,
 *     "requireOnline": true | false
 *   }
 *
 * When requireOnline is true (Tebex parity default), the
 * DeliveryManager defers dispatch until `playerName` is online
 * (Bukkit.getPlayerExact). When false, the command fires
 * immediately as console — for broadcasts / console-only commands.
 */
public final class DeliveryTask {

    @SerializedName("id")
    private String commandId;

    @SerializedName("command")
    private String command;

    @SerializedName("playerName")
    private String playerName;

    @SerializedName("requireOnline")
    private boolean requireOnline;

    @SerializedName("productName")
    private String productName;

    public String commandId() {
        return commandId;
    }

    public String command() {
        return command;
    }

    public String playerName() {
        return playerName;
    }

    public boolean requireOnline() {
        return requireOnline;
    }

    public String productName() {
        return productName;
    }

    /**
     * Test-only / receipt-only factory. The offline-queue drain
     * path reconstructs a partial DeliveryTask for the receipt
     * helper without a wire roundtrip — only the player + product
     * fields are used by the receipt, the rest stay null/default.
     */
    public static DeliveryTask forReceipt(
        String commandId,
        String playerName,
        String productName
    ) {
        DeliveryTask t = new DeliveryTask();
        t.commandId = commandId;
        t.playerName = playerName;
        t.productName = productName;
        return t;
    }
}
