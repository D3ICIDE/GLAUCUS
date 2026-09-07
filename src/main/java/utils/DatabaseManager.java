package utils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

    public class DatabaseManager {
        private static final String URL = "jdbc:postgresql://localhost:5432/glaucus_db";
        private static final String USER = "postgres";
        private static final String PASSWORD = "root"; // Bad Practice Only Here For Testing

        public static Connection getConnection() throws SQLException {
            return DriverManager.getConnection(URL, USER, PASSWORD);
        }
    }

