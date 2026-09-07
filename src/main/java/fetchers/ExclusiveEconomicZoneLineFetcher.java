package fetchers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import utils.DatabaseManager;
import utils.HttpUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;

import static utils.Config.EEZ_Endpoint;

public class ExclusiveEconomicZoneLineFetcher {

    public static void load(String geoJsonString, Connection conn) throws Exception {
        if (geoJsonString == null || geoJsonString.trim().isEmpty()) {
            throw new IllegalArgumentException("Received empty response from EEZ endpoint.");
        }

        JsonElement parsedElement = JsonParser.parseString(geoJsonString);
        if (!parsedElement.isJsonObject()) {
            throw new IllegalArgumentException("Response is not a valid JSON Object. Response:\n" + geoJsonString);
        }

        JsonObject root = parsedElement.getAsJsonObject();
        if (!root.has("features") || root.get("features").isJsonNull()) {
            throw new IllegalArgumentException("GeoJSON missing 'features' array:\n" + geoJsonString);
        }

        JsonArray features = root.getAsJsonArray("features");

        // Targeted specifically to public.india_eez and fetched_at
        String upsertSql = """
            INSERT INTO india_eez (
                feature_id, left_fid, right_fid, geom, fetched_at
            ) VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), CURRENT_TIMESTAMP)
            ON CONFLICT (feature_id) DO UPDATE SET
                left_fid = EXCLUDED.left_fid,
                right_fid = EXCLUDED.right_fid,
                geom = EXCLUDED.geom,
                fetched_at = CURRENT_TIMESTAMP;
            """;

        try (PreparedStatement pstmt = conn.prepareStatement(upsertSql)) {
            for (JsonElement element : features) {
                if (!element.isJsonObject()) continue;

                JsonObject feature = element.getAsJsonObject();

                String featureId = feature.has("id") && !feature.get("id").isJsonNull()
                        ? feature.get("id").getAsString() : null;

                if (featureId == null) continue;

                JsonObject props = feature.has("properties") && !feature.get("properties").isJsonNull()
                        ? feature.getAsJsonObject("properties") : new JsonObject();

                JsonObject geometry = feature.has("geometry") && !feature.get("geometry").isJsonNull()
                        ? feature.getAsJsonObject("geometry") : null;

                if (geometry == null) continue;

                pstmt.setString(1, featureId);
                setInt(pstmt, 2, props, "LEFT_FID");
                setInt(pstmt, 3, props, "RIGHT_FID");
                pstmt.setString(4, geometry.toString());

                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    public static void fetchAndUpdate() throws Exception {
        String geoJson = HttpUtils.sendGET(EEZ_Endpoint, null, null, false);
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            load(geoJson, conn);
            conn.commit();
            System.out.println("EEZ Data successfully loaded into india_eez!");
        }
    }

    private static void setInt(PreparedStatement pstmt, int index, JsonObject obj, String key) throws Exception {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            pstmt.setInt(index, obj.get(key).getAsInt());
        } else {
            pstmt.setNull(index, Types.INTEGER);
        }
    }

    public static void main(String[] args) throws Exception {
        fetchAndUpdate();
    }
}