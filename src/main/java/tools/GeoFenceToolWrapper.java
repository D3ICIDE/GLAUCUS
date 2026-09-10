package tools;

import com.google.gson.*;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import utils.DatabaseManager;
import utils.MapContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class GeoFenceToolWrapper {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Full row, including geometry — used internally / for the frontend map, never sent to the LLM
    private record HazardRow(String hazard_type,String region,String source, String riskLevel, JsonElement message,
                             String fetchedAt,String issued_at,String issued_by,
                             double distanceMeters, String geometryJson) {
        private HazardRow(String hazard_type,String region,String source, String riskLevel, String rawMessage,
                          String fetchedAt,String issued_at,String issued_by,
                          double distanceMeters, String geometryJson) {
            this(hazard_type,region,source, riskLevel, parseJsonMessage(rawMessage), fetchedAt, issued_at, issued_by, distanceMeters, geometryJson);
        }
        private static JsonElement parseJsonMessage(String rawMessage) {
            if (rawMessage == null) return new JsonPrimitive("");
            try { return JsonParser.parseString(rawMessage); }
            catch (Exception e) { return new JsonPrimitive(rawMessage); }
        }
    }

    // Lean summary for the LLM — no geometry, no full raw message blob
    public record HazardSummary(String source, String riskLevel, String messageText, String fetchedAt, double distanceKm) {}

    // ---------------- shared query, not itself an @Tool ----------------

    private static List<HazardRow> queryHazards(double lat, double lon, double radiusMeters) throws Exception {
        String sql = """
        SELECT hazard_type, risk_level, message, fetched_at,region,issued_by,source,issued_at,
               ST_AsGeoJSON(ST_SimplifyPreserveTopology(geom, 0.005)) AS geometry_json,
               ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS dist
        FROM hazard_unified
        WHERE ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
          AND (
              hazard_type != 'eez_boundary_proximity'
              OR NOT EXISTS (
                  SELECT 1 FROM landing_centers lc
                  WHERE ST_DWithin(lc.geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 50000)
              )
          )
        ORDER BY dist ASC
        """;

        List<HazardRow> rows = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // 1,2 — ST_Distance point (lon, lat)
            ps.setDouble(1, lon); ps.setDouble(2, lat);
            // 3,4 — ST_DWithin radius-search point (lon, lat)
            ps.setDouble(3, lon); ps.setDouble(4, lat);
            // 5 — radiusMeters
            ps.setDouble(5, radiusMeters);
            // 6,7 — landing_centers NOT EXISTS point (lon, lat)
            ps.setDouble(6, lon); ps.setDouble(7, lat);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new HazardRow(
                            rs.getString("hazard_type"),rs.getString("region"),
                            rs.getString("source"), rs.getString("risk_level"),
                            rs.getString("message"), rs.getString("fetched_at"),
                            rs.getString("issued_at"),rs.getString("issued_by"),
                            rs.getDouble("dist"), rs.getString("geometry_json")
                    ));
                }
            }
        }
        return rows;
    }

    private static List<HazardRow> queryNearestHazards(double lat, double lon, int limit) throws Exception {
        String sql = """
        SELECT hazard_type,region,source, risk_level, message, fetched_at,issued_by,issued_at,
               ST_AsGeoJSON(geom) AS geometry_json,
               ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS dist
        FROM hazard_unified
        WHERE (
            hazard_type != 'eez_boundary_proximity'
            OR NOT EXISTS (
                SELECT 1 FROM landing_centers lc
                WHERE ST_DWithin(lc.geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 50000)
            )
        )
        ORDER BY geom <-> ST_SetSRID(ST_MakePoint(?, ?), 4326)
        LIMIT ?
        """;

        List<HazardRow> rows = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            // 1,2 — ST_Distance point (lon, lat)
            ps.setDouble(1, lon); ps.setDouble(2, lat);
            // 3,4 — landing_centers NOT EXISTS point (lon, lat) — same query location
            ps.setDouble(3, lon); ps.setDouble(4, lat);
            // 5,6 — ORDER BY KNN point (lon, lat)
            ps.setDouble(5, lon); ps.setDouble(6, lat);
            // 7 — LIMIT
            ps.setInt(7, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new HazardRow(
                            rs.getString("hazard_type"),rs.getString("region"),
                            rs.getString("source"), rs.getString("risk_level"),
                            rs.getString("message"), rs.getString("fetched_at"),
                            rs.getString("issued_at"),rs.getString("issued_by"),
                            rs.getDouble("dist"), rs.getString("geometry_json")
                    ));
                }
            }
        }
        return rows;
    }

    private static JsonElement filterSvaByBoatWidth(JsonElement message, int boatWidth) {
        if (message == null || !message.isJsonArray()) return message;
        JsonArray filteredDays = new JsonArray();
        for (JsonElement dayEl : message.getAsJsonArray()) {
            if (!dayEl.isJsonObject()) continue;
            JsonObject dayObj = dayEl.getAsJsonObject();
            JsonArray advisories = dayObj.has("advisories") ? dayObj.getAsJsonArray("advisories") : null;
            if (advisories == null) continue;
            JsonArray matched = new JsonArray();
            for (JsonElement advEl : advisories) {
                if (!advEl.isJsonObject()) continue;
                JsonObject advObj = advEl.getAsJsonObject();
                if (advObj.has("boat_width") && advObj.get("boat_width").getAsInt() == boatWidth) {
                    matched.add(advObj);
                }
            }
            if (matched.size() > 0) {
                JsonObject filteredDay = new JsonObject();
                filteredDay.add("day", dayObj.get("day"));
                filteredDay.add("advisories", matched);
                filteredDays.add(filteredDay);
            }
        }
        return filteredDays;
    }

    // Converts a full HazardRow -> lean, LLM-safe summary (message flattened to plain text, no geometry)
    private static HazardSummary toSummary(HazardRow r, Integer boatWidth) {
        JsonElement message = r.message();

        if ("sva_advisory".equals(r.source())) {
            if (boatWidth != null) {
                message = filterSvaByBoatWidth(message, boatWidth);
            }
            String messageText = flattenSvaToPlainText(message);
            return new HazardSummary(r.source(), r.riskLevel(), messageText, r.fetchedAt(), r.distanceMeters() / 1000.0);
        }

        String messageText = message.isJsonPrimitive() ? message.getAsString() : message.toString();
        return new HazardSummary(r.source(), r.riskLevel(), messageText, r.fetchedAt(), r.distanceMeters() / 1000.0);
    }

    private static String flattenSvaToPlainText(JsonElement message) {
        if (message == null || !message.isJsonArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement dayEl : message.getAsJsonArray()) {
            if (!dayEl.isJsonObject()) continue;
            JsonObject dayObj = dayEl.getAsJsonObject();
            int day = dayObj.get("day").getAsInt();
            JsonArray advisories = dayObj.getAsJsonArray("advisories");
            for (JsonElement advEl : advisories) {
                JsonObject adv = advEl.getAsJsonObject();
                sb.append("Day ").append(day).append(": ")
                        .append(adv.get("text").getAsString())
                        .append(" [boat width: ").append(adv.get("boat_width").getAsInt()).append("m]")
                        .append("\n");
            }
        }
        return sb.toString().trim();
    }

    // ---------------- LLM-facing tools: ----------------

    @Tool("Fetch the N closest hazards to a location, with type, risk level, message, fetched_at timestamp, and distance in km. " +
            "Does not include map geometry — use for conversational answers only.")
    public static String checkNearestHazards(
            @P("Latitude of the location") double lat,
            @P("Longitude of the location") double lon,
            @P("Number of nearest hazards to return, e.g. 5. This limit should be set to 500 if all hazards are to be fetched.") int limit,
            @P("Boat width category in metres — must be 4, 6, or 7. Should be 'null' if size of the boat is not confirmed.") Integer boatWidth
    ) throws Exception {
        System.out.println("Check Nearest Hazard Called");
        List<HazardRow> rows = queryNearestHazards(lat, lon, limit);


        MapContext.record(lat, lon, "hazard", "checkNearestHazards"); // keep: still useful as a query-point marker
        for (HazardRow r : rows) {
            MapContext.recordHazardGeometry(
                    r.region(), r.issued_by(), r.hazard_type(), r.source(),
                    r.riskLevel(), r.geometryJson(), r.fetchedAt(), r.message(),r.issued_at
            );
        }return gson.toJson(rows.stream().map(r -> toSummary(r, boatWidth)).toList());
    }

    // ---------------- Frontend/map-facing: full geometry, NOT an @Tool, LLM never sees this ----------------

    public static String getHazardsWithGeometryForMap(double lat, double lon, double radiusMeters) throws Exception {
        List<HazardRow> rows = queryHazards(lat, lon, radiusMeters);
        return gson.toJson(rows); // full HazardRow list, including geometryJson and fetchedAt
    }

    public static void main(String[] args) throws Exception {
        System.out.println("LLM-facing (lean):");
        System.out.println(checkNearestHazards(9.9, 76, 10, null));

        System.out.println("\nFrontend-facing (with geometry):");
//        System.out.println(getHazardsWithGeometryForMap(15.0, 74.0, 70000));
//        System.out.println(getHazardsWithGeometryForMap(8.5, 80.0, 70000));
    }
}