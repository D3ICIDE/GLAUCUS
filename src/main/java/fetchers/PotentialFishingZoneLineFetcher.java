package fetchers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fetchers.response.imd.fishermen.PfzLineFeature;
import utils.HttpUtils;
import utils.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

public class PotentialFishingZoneLineFetcher {
//    ================================================================================================================
//                                                    CONFIGS
//    ================================================================================================================
    private static final String URL =
            "https://incois.gov.in/geoserver/PFZ_Automation/ows?service=WFS&version=1.1.0&request=GetFeature&typeName=PFZ_Automation:pfzlines&outputFormat=application/json";
    private static final String SOURCE_NAME = "PfzLinesScout";

    private static final String sql = """
                INSERT INTO pfz_lines
                (feature_id, category, julian_day, year, sno, uid, length_km, geom, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), now())
                ON CONFLICT (feature_id) DO UPDATE SET
                category = EXCLUDED.category, julian_day = EXCLUDED.julian_day,
                year = EXCLUDED.year, length_km = EXCLUDED.length_km,
                geom = EXCLUDED.geom, fetched_at = now()
                """;

// =====================================================================================================================
//                                              FETCH
// =====================================================================================================================
    private static String fetch(){
        return HttpUtils.sendGET(URL, null, null, false);
    }
    //==================================================================================================================
    //                                          PARSE
    //==================================================================================================================
    public static List<PfzLineFeature> parse(String json){
        List<PfzLineFeature> featuresList = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray features = root.getAsJsonArray("features");
        for (JsonElement item : features) {
            try {
                JsonObject feature = item.getAsJsonObject();
                JsonObject props = feature.getAsJsonObject("properties");
                JsonObject geom = feature.getAsJsonObject("geometry");

                PfzLineFeature pfzFeature = new PfzLineFeature(
                        feature.get("id").getAsString(),
                        getStringOrNull(props, "Category"),
                        props.get("Julian_day").getAsInt(),
                        props.get("Year").getAsInt(),
                        getStringOrNull(props, "Sno"),
                        getStringOrNull(props, "UID"),
                        props.get("Length").getAsDouble(),
                        geom.toString()
                );
                featuresList.add(pfzFeature);
            } catch (Exception e) {
                System.err.println("Skipped parsing feature: " + e.getMessage());
            }
        }
        return featuresList;
    }

//======================================================================================================================
//                                                       UPDATE
//======================================================================================================================
    public static int store(List<PfzLineFeature> features) throws Exception {

        int successCount = 0;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PfzLineFeature feature : features) {
                try {
                    ps.setString(1, feature.featureId());
                    ps.setString(2, feature.category());
                    ps.setInt(3, feature.julianDay());
                    ps.setInt(4, feature.year());
                    ps.setString(5, feature.sno());
                    ps.setString(6, feature.uid());
                    ps.setDouble(7, feature.lengthKm());
                    ps.setString(8, feature.geomJson());
                    ps.executeUpdate();
                    successCount++;
                } catch (Exception e) {
                    System.err.println("Skipped feature: " + e.getMessage());
                }
            }
        }
        return successCount;
    }

    private static String getStringOrNull(JsonObject obj, String memberName) {
        return obj.has(memberName) && !obj.get(memberName).isJsonNull()
                ? obj.get(memberName).getAsString()
                : null;
    }

    public static void fetchAndStore() {
        int successCount = 0;
        String failureMessage = null;
        try {
            successCount = store(parse(fetch()));
        } catch (Exception e) {
            failureMessage = e.getMessage();
        }
        utils.FetchLogger.log(SOURCE_NAME, successCount, failureMessage);
    }

    public static void main(String[] args) {
        fetchAndStore();
    }
}
