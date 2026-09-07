package tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import utils.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class OceanDataToolWrapper {


    // A single scalar result — sst or wave_height
    public record ScalarResult(double value, int pointsUsed) {}


    public record VectorResult(double speed, double direction, int pointsUsed) {}

    private static final double DEFAULT_RADIUS_METERS = 50000;

    // ======================= scalar fetch (sst, wave_height) =================================

    private record ScalarRow(double value, double distanceMeters) {}

    private static List<ScalarRow> fetchScalarNearby(String variable, double lat, double lon, double radiusMeters) throws Exception {
        String sql = """
            SELECT value,
                   ST_Distance(
                       ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography,
                       ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                   ) AS dist
            FROM ocean_point_readings
            WHERE variable = ?
              AND data_date = CURRENT_DATE
              AND ST_DWithin(
                    ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    ?
                  )
            """;

        List<ScalarRow> rows = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, lon); ps.setDouble(2, lat);
            ps.setString(3, variable);
            ps.setDouble(4, lon); ps.setDouble(5, lat);
            ps.setDouble(6, radiusMeters);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ScalarRow(rs.getDouble("value"), rs.getDouble("dist")));
                }
            }
        }
        return rows;
    }

    private static ScalarResult calculateScalarAverage(List<ScalarRow> points) {
        if (points.isEmpty()) return new ScalarResult(Double.NaN, 0);

        double weightedSum = 0, weightSum = 0;
        for (ScalarRow p : points) {
            double weight = 1.0 / (1.0 + p.distanceMeters()); // inverse-distance weighting
            weightedSum += p.value() * weight;
            weightSum += weight;
        }
        return new ScalarResult(weightedSum / weightSum, points.size());
    }

    // ---------------- vector fetch (current, wind) — speed+direction joined by exact point ----------------

    private record VectorRow(double speed, double directionDeg, double distanceMeters) {}

    private static List<VectorRow> fetchVectorPairsNearby(String speedVar, String directionVar,
                                                          double lat, double lon, double radiusMeters) throws Exception {
        // Self-join on identical (latitude, longitude, data_date) so each speed
        // reading is paired with the direction reading from the exact same grid
        // point — never assumes two separate queries return rows in matching order.
        String sql = """
            SELECT s.value AS speed, d.value AS direction,
                   ST_Distance(
                       ST_SetSRID(ST_MakePoint(s.longitude, s.latitude), 4326)::geography,
                       ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                   ) AS dist
            FROM ocean_point_readings s
            JOIN ocean_point_readings d
              ON s.latitude = d.latitude
             AND s.longitude = d.longitude
             AND s.data_date = d.data_date
            WHERE s.variable = ?
              AND d.variable = ?
              AND s.data_date = CURRENT_DATE
              AND ST_DWithin(
                    ST_SetSRID(ST_MakePoint(s.longitude, s.latitude), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    ?
                  )
            """;

        List<VectorRow> rows = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, lon); ps.setDouble(2, lat);
            ps.setString(3, speedVar);
            ps.setString(4, directionVar);
            ps.setDouble(5, lon); ps.setDouble(6, lat);
            ps.setDouble(7, radiusMeters);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new VectorRow(rs.getDouble("speed"), rs.getDouble("direction"), rs.getDouble("dist")));
                }
            }
        }
        return rows;
    }

    private static VectorResult calculateVectorAverage(List<VectorRow> pairs) {
        if (pairs.isEmpty()) return new VectorResult(Double.NaN, Double.NaN, 0);

        // Decompose each speed+direction into u/v vector components, average those
        // (inverse-distance weighted), then recombine — never average degrees directly.
        double sumU = 0, sumV = 0, weightSum = 0;
        for (VectorRow p : pairs) {
            double weight = 1.0 / (1.0 + p.distanceMeters());
            double u = p.speed() * Math.sin(Math.toRadians(p.directionDeg()));
            double v = p.speed() * Math.cos(Math.toRadians(p.directionDeg()));
            sumU += u * weight;
            sumV += v * weight;
            weightSum += weight;
        }

        double avgU = sumU / weightSum;
        double avgV = sumV / weightSum;
        double avgSpeed = Math.hypot(avgU, avgV);
        double avgDirection = (Math.toDegrees(Math.atan2(avgU, avgV)) + 360) % 360;

        return new VectorResult(avgSpeed, avgDirection, pairs.size());
    }

    //====================================================TOOLS========================================================
    @Tool("Fetch the average sea surface temperature (SST) in degrees Celsius within a radius of a location, averaged from nearby ocean readings.")
    public static ScalarResult fetchSst(
            @P("Latitude of the location") double lat,
            @P("Longitude of the location") double lon,
            @P("Search radius in meters around the location, e.g. 50000 for 50km") double radiusMeters)
            throws Exception {
        return calculateScalarAverage(fetchScalarNearby("sst", lat, lon, radiusMeters));
    }

    @Tool("Fetch the average sea surface temperature (SST) in degrees Celsius within a default 50km radius of a location. Use this when no specific radius is given.")
    public static ScalarResult fetchSstDefaultRadius(
            @P("Latitude of the location") double lat,
            @P("Longitude of the location") double lon)
            throws Exception {
        return fetchSst(lat, lon, DEFAULT_RADIUS_METERS);
    }

    @Tool("Fetch the average wave height in meters within a radius of a location, averaged from nearby ocean readings.")
    public static ScalarResult fetchWave(
            @P("Latitude of the location") double lat,
            @P("Longitude of the location") double lon,
            @P("Search radius in meters around the location, e.g. 50000 for 50km") double radiusMeters)
            throws Exception {
        return calculateScalarAverage(fetchScalarNearby("wave_height", lat, lon, radiusMeters));
    }

    @Tool("Fetch the average wave height in meters within a default 50km radius of a location. Use this when no specific radius is given.")
    public static ScalarResult fetchWaveDefaultRadius(
            @P("Latitude of the location") double lat,
            @P("Longitude of the location") double lon)
            throws Exception {
        return fetchWave(lat, lon, DEFAULT_RADIUS_METERS);
    }

    @Tool("Fetch the average ocean current speed (m/s) and direction (degrees, meteorological convention — direction the current flows toward) within a radius of a location. Direction and speed are vector-averaged together, not averaged independently.")
    public static VectorResult fetchCurrent(
            @P("Latitude of the location") double lat,
            @P("Longitude of the location") double lon,
            @P("Search radius in meters around the location, e.g. 50000 for 50km") double radiusMeters)
            throws Exception {
        return calculateVectorAverage(fetchVectorPairsNearby("current_speed", "current_direction", lat, lon, radiusMeters));
    }

    @Tool("Fetch the average ocean current speed (m/s) and direction (degrees) within a default 50km radius of a location. Use this when no specific radius is given.")
    public static VectorResult fetchCurrentDefaultRadius(
            @P("Latitude of the location") double lat,
            @P("Longitude of the location") double lon)
            throws Exception {
        return fetchCurrent(lat, lon, DEFAULT_RADIUS_METERS);
    }

    @Tool("Fetch the average wind speed (m/s) and direction (degrees, meteorological convention — direction the wind blows toward) within a radius of a location. Direction and speed are vector-averaged together, not averaged independently.")
    public static VectorResult fetchWind(
            @P("Latitude of the location") double lat,
            @P("Longitude of the location") double lon,
            @P("Search radius in meters around the location, e.g. 50000 for 50km") double radiusMeters)
            throws Exception {
        return calculateVectorAverage(fetchVectorPairsNearby("wind_speed", "wind_direction", lat, lon, radiusMeters));
    }

    @Tool("Fetch the average wind speed (m/s) and direction (degrees) within a default 50km radius of a location. Use this when no specific radius is given.")
    public static VectorResult fetchWindDefaultRadius(
            @P("Latitude of the location") double lat,
            @P("Longitude of the location") double lon)
            throws Exception {
        return fetchWind(lat, lon, DEFAULT_RADIUS_METERS);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("SST: " + fetchSstDefaultRadius(26, 81));
        System.out.println("Wave: " + fetchWaveDefaultRadius(15.0, 74.0));
        System.out.println("Current: " + fetchCurrentDefaultRadius(15.0, 74.0));
        System.out.println("Wind: " + fetchWindDefaultRadius(15.0, 74.0));
    }
}