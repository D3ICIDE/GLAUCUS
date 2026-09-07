package utils;

import java.sql.Connection;
import java.sql.PreparedStatement;

public class FetchLogger {

    public static void log(String sourceName, int rowsInserted, String errorMessage) {
        String status = (errorMessage == null) ? "OK" : "FAILED";
        String message = (errorMessage == null)
                ? "Inserted " + rowsInserted + " row(s)"
                : errorMessage;

        String sql = "INSERT INTO fetch_log (source_name, status, message, fetched_at) VALUES (?, ?, ?, now())";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceName);
            ps.setString(2, status);
            ps.setString(3, message);
            ps.executeUpdate();
        } catch (Exception e) {
            System.out.println("Failed to write fetch_log: " + e.getMessage());
        }
    }
}
