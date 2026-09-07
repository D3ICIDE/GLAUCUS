package tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import utils.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class LandingCenterToolWrapper {
    public record LandingCenter(String name, String district, String sector, double distanceMeters) {
    }

    public static List<LandingCenter> fetchNearest(double lat, double lon, int limit) throws Exception {
        String sql = """
            
                SELECT lc_name, district_name, sector_name,
                   ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS dist
                            FROM landing_centers
                            ORDER
                BY dist
        
                   LIMIT ?
            """;
        List<LandingCenter> results = new ArrayList<>();
        try (
                Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.
                        prepareStatement(sql)
        ) {
            ps.setDouble(1, lon);
            ps.setDouble(2, lat);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new LandingCenter(
                            rs.getString("lc_name"), rs.getString(
                            "district_name"),
                            rs.
                                    getString(
                                            "sector_name"), rs.getDouble("dist")
                    ));
                }
            }
        }
        return results;
    }
    @Tool("Fetches all the Landing Centres Available")
    public static List<LandingCenter> fetchAll(
            @P("Latitude of current location ") double lat,@P("Longitude of current location") double lon) throws Exception {
        String sql = """
            SELECT lc_name, district_name, sector_name,
                   ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS dist
            FROM landing_centers
            ORDER BY dist
            """;
        List<LandingCenter> results = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, lon);
            ps.setDouble(2, lat);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new LandingCenter(
                            rs.getString("lc_name"), rs.getString("district_name"),
                            rs.getString("sector_name"), rs.getDouble("dist")
                    ));
                }
            }
        }
        return results;
    }

    public static void main(String[] args) {
        try{
        System.out.println(fetchAll(12.8,78.5));
    }catch (Exception e){
            System.out.println("Failed");
        }
        }
}



