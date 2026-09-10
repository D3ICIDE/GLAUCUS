package utils;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MapContext {
// ---------------- POI markers, recorded directly by PoiToolWrapper ----------------

    public record PoiEntry(String poiType, String name, double lat, double lon,
                           String metadata, double distanceKm, String lastUpdated) {}

    private static final ThreadLocal<List<PoiEntry>> POI_ENTRIES =
            ThreadLocal.withInitial(ArrayList::new);

    public static void recordPoi(String poiType, String name, double lat, double lon,
                                 String metadata, double distanceKm, String lastUpdated) {
        POI_ENTRIES.get().add(new PoiEntry(poiType, name, lat, lon, metadata, distanceKm, lastUpdated));
    }

    public static JsonArray drainPoi() {
        List<PoiEntry> entries = POI_ENTRIES.get();
        POI_ENTRIES.remove();

        JsonArray out = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (PoiEntry e : entries) {
            JsonObject obj = new JsonObject();
            obj.addProperty("poi_type", e.poiType());
            obj.addProperty("name", e.name());
            obj.addProperty("lat", e.lat());
            obj.addProperty("lon", e.lon());
            obj.addProperty("metadata", e.metadata());
            obj.addProperty("distanceKm", e.distanceKm());
            obj.addProperty("lastUpdated", e.lastUpdated());
            if (seen.add(obj.toString())) out.add(obj);
        }
        return out;
    }
    // ---------------- new: full hazard geometry, recorded directly by the tool that found it ----------------

    public record HazardGeometryEntry(String region,String issuedBy,String type,String source, String riskLevel, String geometryJson, String fetchedAt,JsonElement message,String issued_at) {}

    private static final ThreadLocal<List<HazardGeometryEntry>> HAZARD_GEOMETRY =
            ThreadLocal.withInitial(ArrayList::new);

    public static void recordHazardGeometry(String region, String issuedBy, String type, String source, String riskLevel, String geometryJson, String fetchedAt, JsonElement message,String issued_at) {
        HAZARD_GEOMETRY.get().add(new HazardGeometryEntry(region,issuedBy,type,source,riskLevel, geometryJson, fetchedAt,message,issued_at));
    }
    public static JsonArray drainHazardGeometry() {
        List<HazardGeometryEntry> entries = HAZARD_GEOMETRY.get();
        HAZARD_GEOMETRY.remove();

        JsonArray out = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (HazardGeometryEntry e : entries) {
            JsonObject obj = new JsonObject();
            obj.addProperty("hazard_type", e.type());
            obj.addProperty("region", e.region());
            obj.addProperty("source", e.source());
            obj.addProperty("riskLevel", e.riskLevel());
            obj.addProperty("issued_by", e.issuedBy());
            obj.addProperty("issued_at", e.issued_at());
            obj.addProperty("fetchedAt", e.fetchedAt());
            obj.add("message", e.message());
            obj.add("geometry", e.geometryJson() != null
                    ? JsonParser.parseString(e.geometryJson())
                    : JsonNull.INSTANCE);
            String key = obj.toString();
            if (seen.add(key)) out.add(obj);
        }
        return out;
    }
}