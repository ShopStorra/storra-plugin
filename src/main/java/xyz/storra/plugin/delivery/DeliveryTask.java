package xyz.storra.plugin.delivery;

import com.google.gson.annotations.SerializedName;

/**
 * One unit of work pulled from `/api/v1/plugin/pending`. Mirrors
 * the JSON shape Storra's `delivery_queue` row serializes to.
 *
 * Serialized field names follow the Storra-side snake_case schema;
 * Java fields stay camelCase via @SerializedName so the rest of
 * the plugin reads naturally.
 *
 * The `requireOnline` flag tells DeliveryManager whether a missing
 * player should defer the task to the offline queue (deliver when
 * they next join) or fail immediately.
 */
public final class DeliveryTask {

    @SerializedName("task_id")
    private long taskId;

    @SerializedName("product_id")
    private String productId;

    @SerializedName("command")
    private String command;

    @SerializedName("player_name")
    private String playerName;

    @SerializedName("player_uuid")
    private String playerUuid;

    @SerializedName("require_online")
    private boolean requireOnline;

    public long taskId() {
        return taskId;
    }

    public String productId() {
        return productId;
    }

    public String command() {
        return command;
    }

    public String playerName() {
        return playerName;
    }

    public String playerUuid() {
        return playerUuid;
    }

    public boolean requireOnline() {
        return requireOnline;
    }
}
