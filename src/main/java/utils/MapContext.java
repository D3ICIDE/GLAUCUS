package utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MapContext {
    public record InterestPoint(double lat, double lon, String kind, String reason) {}

    private static final ThreadLocal<List<InterestPoint>> POINTS =
            ThreadLocal.withInitial(ArrayList::new);

    public static void record(double lat, double lon, String kind, String reason) {
        POINTS.get().add(new InterestPoint(lat, lon, kind, reason));
    }

    public static List<InterestPoint> drain() {
        List<InterestPoint> points = POINTS.get();
        POINTS.remove(); // avoid leaking across requests on the same pooled thread
        return points;
    }

    // ---------------- new: full hazard geometry, recorded directly by the tool that found it ----------------

    public record HazardGeometryEntry(String source, String riskLevel, String geometryJson, String fetchedAt) {}

    private static final ThreadLocal<List<HazardGeometryEntry>> HAZARD_GEOMETRY =
            ThreadLocal.withInitial(ArrayList::new);

    public static void recordHazardGeometry(String source, String riskLevel, String geometryJson, String fetchedAt) {
        HAZARD_GEOMETRY.get().add(new HazardGeometryEntry(source, riskLevel, geometryJson, fetchedAt));
    }

    public static JsonArray drainHazardGeometry() {
        List<HazardGeometryEntry> entries = HAZARD_GEOMETRY.get();
        HAZARD_GEOMETRY.remove(); // same leak-avoidance reasoning as drain() above

        JsonArray out = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (HazardGeometryEntry e : entries) {
            JsonObject obj = new JsonObject();
            obj.addProperty("source", e.source());
            obj.addProperty("riskLevel", e.riskLevel());
            obj.addProperty("fetchedAt", e.fetchedAt());
            obj.add("geometry", JsonParser.parseString(e.geometryJson()));
            String key = obj.toString();
            if (seen.add(key)) out.add(obj);
        }
        return out;
    }
}