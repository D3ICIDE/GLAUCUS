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

import static utils.Config.Landing_Center_Endpoint;

public class LandingCentresFetcher {
    //==================================================================================================================
    //                                                          CONFIG
    //==================================================================================================================
   private static String upsertSql = """
            
                INSERT INTO landing_centers (
                lc_unique_id, object_id, lc_name, sector_name, sector_id,
                district_name, forecast_id, direction, bearing, depth_from, depth_to,
                updated_at, validity_date, geom
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ST_SetSRID(ST_MakePoint(?, ?), 4326))
            ON CONFLICT (lc_unique_id) DO UPDATE SET
                lc_name = EXCLUDED.lc_name,
                sector_name = EXCLUDED.sector_name,
                district_name = EXCLUDED.district_name,
                forecast_id = EXCLUDED.forecast_id,
                direction = EXCLUDED.direction,
                bearing = EXCLUDED.bearing,
                updated_at = EXCLUDED.updated_at,
                validity_date = EXCLUDED.validity_date,
                geom = EXCLUDED.geom;
            """;



    public static void load(String geoJsonString) throws Exception {
        if (geoJsonString == null || geoJsonString.trim().isEmpty()) {
            throw new IllegalArgumentException("Received empty or null response from GeoServer endpoint.");
        }

        JsonElement parsedElement = JsonParser.parseString(geoJsonString);
        JsonArray features = getJsonElements(geoJsonString, parsedElement);


        try(Connection conn = DatabaseManager.getConnection())
        {
        try (PreparedStatement pstmt = conn.prepareStatement(
                upsertSql)) {
            for (JsonElement
                    element : features) {
                if (!

                    element.isJsonObject()) continue;

                JsonObject feature = element.getAsJsonObject();
                JsonObject props = feature.has("properties"
                            ) && !feature.get("properties").isJsonNull()
                        ? feature.getAsJsonObject("properties") : new JsonObject();

                JsonArray coords = feature.getAsJsonObject("geometry").
                    getAsJsonArray("coordinates");

                double lon = coords.get(0).getAsDouble();
                double
                    lat = coords.get(1).getAsDouble();
                    // Safe field bindings using helper methods
                pstmt.setString(1, getString(props, "LC_UNIQUE_"));
                setInt(pstmt, 2, props, "OBJECTID");
                pstmt.
                    setString(3, getString(props, "LC_NAME"));
                pstmt.setString(4, getString(props, "SECTOR_NAM"));
                pstmt.setString(5, getString(props,
                    "SECTOR_ID"));
                pstmt.setString(6, getString(
                    props, "DIST_NAME"));
                setInt(pstmt, 7, props, "FORECAST_I");
                pstmt.setString(8, getString(props, "DIRECTION"));
                setInt(pstmt, 9, props, "BEARING");
                    setInt(pstmt, 10, props, "DEPTH_FROM");
                setInt(pstmt, 11
                    , props, "DEPTH_TO");
                pstmt.setString(12, getString(

                    props,
                "UPDATED_DA"));
                pstmt.
        setString(13,
    getString(props, "VALIDITY_D"));
                pstmt.setDouble(14, lon);
                pstmt.setDouble(15, lat);

                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }
        }

    private static JsonArray getJsonElements(String geoJsonString, JsonElement parsedElement) {
        if (!parsedElement.isJsonObject()) {
            throw new IllegalArgumentException("Response is not a valid JSON Object. Raw output:\n" + geoJsonString);
        }

        JsonObject root = parsedElement.getAsJsonObject();

        // Check if 'features' array exists in the response
        if (!root.has("features") || root.get("features").isJsonNull()) {
            throw new IllegalArgumentException("GeoJSON does not contain a valid 'features' array. " +
                    "Endpoint may have returned an error:\n" + geoJsonString);
        }

        JsonArray features = root.getAsJsonArray("features");
        return features;
    }

    public static void fetchAndUpdate() throws Exception {
        String geoJson = HttpUtils.sendGET(Landing_Center_Endpoint, null, null, false);
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            load(geoJson);
            conn.commit();
        }
    }

    // Helper methods for null-safe Gson parsing
    private static String getString(JsonObject obj, String key) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : null;
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