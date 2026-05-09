package xyz.storra.plugin.delivery;

import xyz.storra.plugin.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Rolling local log of delivered + failed tasks. Powers the
 * `/storra history` admin command.
 *
 * Bounded retention: oldest rows beyond N (config:
 * persistence.history-retention) are pruned on each insert. Cheap
 * — single DELETE keyed on a bounded subquery.
 */
public final class DeliveryHistory {

    private final Database database;
    private final int retention;

    public DeliveryHistory(Database database, int retention) {
        this.database = database;
        this.retention = retention;
    }

    public void recordDelivered(DeliveryTask task) throws SQLException {
        record(task.taskId(), "delivered", task.playerName(), task.command(), null);
    }

    public void recordFailed(DeliveryTask task, String reason) throws SQLException {
        record(task.taskId(), "failed", task.playerName(), task.command(), reason);
    }

    private void record(long taskId, String status, String playerName, String command, String reason) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement st = conn.prepareStatement(
            "INSERT OR REPLACE INTO delivery_history " +
            "(task_id, status, player_name, command, reason) VALUES (?, ?, ?, ?, ?)"
        )) {
            st.setLong(1, taskId);
            st.setString(2, status);
            st.setString(3, playerName);
            st.setString(4, command);
            st.setString(5, reason);
            st.executeUpdate();
        }
        // Prune past retention. Cheap — bounded by N rows.
        try (PreparedStatement st = conn.prepareStatement(
            "DELETE FROM delivery_history WHERE task_id NOT IN (" +
            "  SELECT task_id FROM delivery_history " +
            "  ORDER BY recorded_at DESC LIMIT ?" +
            ")"
        )) {
            st.setInt(1, retention);
            st.executeUpdate();
        }
    }

    public List<HistoryRow> recent(int limit) throws SQLException {
        Connection conn = database.connection();
        List<HistoryRow> rows = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(
            "SELECT task_id, status, player_name, command, reason, recorded_at " +
            "FROM delivery_history ORDER BY recorded_at DESC LIMIT ?"
        )) {
            st.setInt(1, limit);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    rows.add(new HistoryRow(
                        rs.getLong("task_id"),
                        rs.getString("status"),
                        rs.getString("player_name"),
                        rs.getString("command"),
                        rs.getString("reason"),
                        rs.getLong("recorded_at")
                    ));
                }
            }
        }
        return rows;
    }

    public record HistoryRow(
        long taskId,
        String status,
        String playerName,
        String command,
        String reason,
        long recordedAtMs
    ) {}
}
