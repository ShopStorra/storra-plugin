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
 *      the target player isn't in `Bukkit.getOnlinePlayers()`.
 *   2. enqueue() writes a row keyed on (player_uuid, task_id).
 *   3. PlayerJoinListener (ticket 7) calls drainForPlayer() on
 *      PlayerJoinEvent and DeliveryManager runs the rows.
 *   4. dequeue() removes the row once the command runs.
 *
 * SQLite primary key is task_id (server-side unique). Index on
 * player_uuid for the join-event lookup. Schema is created in
 * Database.open().
 */
public final class OfflineQueue {

    private final Database database;

    public OfflineQueue(Database database) {
        this.database = database;
    }

    public void enqueue(long taskId, String playerUuid, String command) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement st = conn.prepareStatement(
            "INSERT OR REPLACE INTO delivery_offline_queue " +
            "(task_id, player_uuid, command) VALUES (?, ?, ?)"
        )) {
            st.setLong(1, taskId);
            st.setString(2, playerUuid);
            st.setString(3, command);
            st.executeUpdate();
        }
    }

    public List<QueuedTask> drainForPlayer(String playerUuid) throws SQLException {
        Connection conn = database.connection();
        List<QueuedTask> rows = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(
            "SELECT task_id, player_uuid, command FROM delivery_offline_queue " +
            "WHERE player_uuid = ? ORDER BY queued_at ASC"
        )) {
            st.setString(1, playerUuid);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    rows.add(new QueuedTask(
                        rs.getLong("task_id"),
                        rs.getString("player_uuid"),
                        rs.getString("command")
                    ));
                }
            }
        }
        return rows;
    }

    public void dequeue(long taskId) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement st = conn.prepareStatement(
            "DELETE FROM delivery_offline_queue WHERE task_id = ?"
        )) {
            st.setLong(1, taskId);
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

    public record QueuedTask(long taskId, String playerUuid, String command) {}
}
