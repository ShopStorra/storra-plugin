package xyz.storra.plugin.delivery;

import xyz.storra.plugin.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent buffer for tasks whose target player isn't online.
 *
 * Workflow:
 *   1. DeliveryManager pulls a task with `requireOnline=true` and
 *      Bukkit.getPlayerExact(name) returns null.
 *   2. enqueue() writes a row keyed on (player_name_lower, command_id).
 *   3. PlayerJoinListener calls drainForPlayer() on PlayerJoinEvent
 *      with the joining player's lowercased name; DeliveryManager
 *      then runs the rows + fires the in-game receipt.
 *   4. dequeue() removes the row once the command runs.
 *
 * `product_name` is persisted alongside the command so the
 * DeliveryReceipt can name the package on drain — without it the
 * receipt would have to fall back to a generic "your purchase".
 *
 * Primary key is command_id (server-side UUID, globally unique).
 * Lookup index is on player_name_lower for the join-event drain.
 */
public final class OfflineQueue {

    private final Database database;

    public OfflineQueue(Database database) {
        this.database = database;
    }

    public void enqueue(
        String commandId,
        String playerNameLower,
        String command,
        String productName
    ) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement st = conn.prepareStatement(
            "INSERT OR REPLACE INTO delivery_offline_queue " +
            "(command_id, player_name_lower, command, product_name) VALUES (?, ?, ?, ?)"
        )) {
            st.setString(1, commandId);
            st.setString(2, playerNameLower);
            st.setString(3, command);
            st.setString(4, productName);
            st.executeUpdate();
        }
    }

    public List<QueuedTask> drainForPlayer(String playerNameLower) throws SQLException {
        Connection conn = database.connection();
        List<QueuedTask> rows = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(
            "SELECT command_id, player_name_lower, command, product_name FROM delivery_offline_queue " +
            "WHERE player_name_lower = ? ORDER BY queued_at ASC"
        )) {
            st.setString(1, playerNameLower);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    rows.add(new QueuedTask(
                        rs.getString("command_id"),
                        rs.getString("player_name_lower"),
                        rs.getString("command"),
                        rs.getString("product_name")
                    ));
                }
            }
        }
        return rows;
    }

    public void dequeue(String commandId) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement st = conn.prepareStatement(
            "DELETE FROM delivery_offline_queue WHERE command_id = ?"
        )) {
            st.setString(1, commandId);
            st.executeUpdate();
        }
    }

    public int depth() throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement st = conn.prepareStatement(
            "SELECT COUNT(*) FROM delivery_offline_queue"
        ); ResultSet rs = st.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public record QueuedTask(
        String commandId,
        String playerNameLower,
        String command,
        String productName
    ) {}
}
