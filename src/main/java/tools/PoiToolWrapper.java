package tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import utils.DatabaseManager;
import utils.MapContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class PoiToolWrapper {
    public enum PoiType {
        LANDING_CENTRE,
        PORT,
        PFZ,

    }

    public record Poi(String poiType, String name, double lat, double lon, String metadata, double distanceKm, String lastUpdated) {}

    // Distance in km, computed from the view's geom column (geography cast = accurate great-circle distance)
    private static final String DISTANCE_SQL =
            "ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) / 1000";

    // 1. Nearest POI overall, or nearest of a specific type
    @Tool("Searches for 3 nearest Point of Interests")
    public static Poi findNearest(@P("The latitude of the center location (e.g., 19.25)") double lat,
                                  @P("The longitude of the center location (e.g., 85.70)") double lon,
                                  @P("Optional. The specific category of POI to filter by (e.g., 'landing centres', 'port')." +
                                          " Pass null to retrieve all nearby POIs regardless of type.") PoiType poiType)
            throws Exception {
        System.out.println("findNearestPoi called" + lat + lon+ poiType);
        String sql = "SELECT poi_type, name, latitude, longitude, metadata, last_updated, " +
                DISTANCE_SQL + " AS dist_km " +
                "FROM poi_unified " +
                (poiType != null ? "WHERE poi_type = ? " : "") +
                "ORDER BY dist_km ASC LIMIT 3";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, lon);   // ST_MakePoint(lon, lat) - PostGIS is x,y = lon,lat
            ps.setDouble(2, lat);

            if (poiType != null){
                String poiTypeString = poiType.name().toLowerCase();
                ps.setString(3, poiTypeString);
            }

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Poi poi = new Poi(rs.getString("poi_type"), rs.getString("name"),
                            rs.getDouble("latitude"), rs.getDouble("longitude"),
                            rs.getString("metadata"), rs.getDouble("dist_km"),
                            rs.getString("last_updated"));
                    MapContext.recordPoi(poi.poiType(), poi.name(), poi.lat(), poi.lon(),
                            poi.metadata(), poi.distanceKm(), poi.lastUpdated());
                    return poi;
                }
            }
        }
        return null;
    }

    // 2. All POIs within a radius, optionally filtered by type
    @Tool("Searches for Points of Interest (POIs) within a specific radius (in kilometers) around a given geographic coordinate. Use this to find nearby locations like ports, landing centres, or fishing zones.")
    public static List<Poi> findWithinRadius(
            @P("The latitude of the center location (e.g., 19.25)") double lat,
            @P("The longitude of the center location (e.g., 85.70)") double lon,
            @P("The search radius in kilometers (e.g., 10.5)") double radiusKm,
            @P("Optional. The specific category of POI to filter by (e.g., 'landing center', 'port'). Pass null to retrieve all nearby POIs regardless of type.") PoiType poiType
    ) throws Exception {
        System.out.println("Another PoI called, Query:" + lat+lon+radiusKm+poiType);
        String sql = "SELECT poi_type, name, latitude, longitude, metadata, last_updated, " +
                DISTANCE_SQL + " AS dist_km " +
                "FROM poi_unified " +
                "WHERE ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?) " +
                (poiType != null ? "AND poi_type = ? " : "") +
                "ORDER BY dist_km ASC";

        List<Poi> results = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, lon); ps.setDouble(2, lat);   // for SELECT dist_km
            ps.setDouble(3, lon); ps.setDouble(4, lat);   // for WHERE ST_DWithin
            ps.setDouble(5, radiusKm * 1000);             // ST_DWithin on geography expects meters
            if (poiType != null){
                String nPoiType = poiType.name().toLowerCase();
                ps.setString(6, nPoiType);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Poi poi = new Poi(rs.getString("poi_type"), rs.getString("name"),
                            rs.getDouble("latitude"), rs.getDouble("longitude"),
                            rs.getString("metadata"), rs.getDouble("dist_km"),
                            rs.getString("last_updated"));
                    MapContext.recordPoi(poi.poiType(), poi.name(), poi.lat(), poi.lon(),
                            poi.metadata(), poi.distanceKm(), poi.lastUpdated());
                    results.add(poi);
                }
            }
        }
        return results;
    }

    // 3. Look up a specific named POI (e.g. "is there a buoy near Tuticorin")

    @Tool("This tool is used to search for Point Of Interest by their name. For example, by the name of the port or the name of the landing station")
    public static List<Poi> findByName(
            @P("This parameter takes the name of the Point Of Interest")String nameQuery) throws Exception {
        System.out.println("PoI Tool called");
        System.out.println(nameQuery);
        String sql = """
                SELECT poi_type, name, latitude, longitude, metadata, last_updated
                FROM poi_unified WHERE name ILIKE ? LIMIT 10
                """;

        List<Poi> results = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + nameQuery + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Poi poi = new Poi(rs.getString("poi_type"), rs.getString("name"),
                            rs.getDouble("latitude"), rs.getDouble("longitude"),
                            rs.getString("metadata"), -1, rs.getString("last_updated"));
                    MapContext.recordPoi(poi.poiType(), poi.name(), poi.lat(), poi.lon(),
                            poi.metadata(), poi.distanceKm(), poi.lastUpdated());
                    results.add(poi);
                }
            }
        }
        return results;
    }

    public static void main(String[] args) throws Exception {
//        System.out.println(findByName("Ghivali"));
        System.out.println(findNearest(26.8827239732141, 81.0584415441241,PoiType.LANDING_CENTRE));
    }
}