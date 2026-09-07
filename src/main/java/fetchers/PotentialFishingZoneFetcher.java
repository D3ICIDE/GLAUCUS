package fetchers;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import fetchers.response.imd.fishermen.PfzPoint;
import utils.DatabaseManager;
import utils.FetchLogger;
import utils.HttpUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PotentialFishingZoneFetcher {
    //  ====================================================================================================================
//                                                      CONFIGS
//  ====================================================================================================================
    private static final String SOURCE_NAME = "PfzScout";

    private static final String primaryURL = "https://incois.gov.in/MarineFisheries/TextDataHome?mfid=1&request_locale=en";
    private static final String secondaryURL = "https://incois.gov.in/MarineFisheries/TextData?secid=";
    private static final String tertiaryURL = "https://incois.gov.in/MarineFisheries/formattedForecast.action?distanceformat=km&depthformat=metre&latlongformat=dms";
    private static final Pattern DEVANAGARI = Pattern.compile("[\\u0900-\\u097F]+");
    private static final Pattern DUPLICATE_NAME = Pattern.compile("^(.+?)\\s+\\1$");

    private static final String sql = """
                    INSERT INTO pfz_point
                    (sec_id, location_name, direction, bearing_deg, dist_min_km, dist_max_km, depth_min_m, depth_max_m, latitude, longitude, fetched_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (location_name, sec_id) DO UPDATE SET
                    direction = EXCLUDED.direction,
                    bearing_deg = EXCLUDED.bearing_deg,
                    dist_min_km = EXCLUDED.dist_min_km,
                    dist_max_km = EXCLUDED.dist_max_km,
                    depth_min_m = EXCLUDED.depth_min_m,
                    depth_max_m = EXCLUDED.depth_max_m,
                    latitude = EXCLUDED.latitude,
                    longitude = EXCLUDED.longitude,
                    fetched_at = now()"""
            ;

    // Anything with fetched_at older than this run's start (captured before any
    // upserts happen) is a PFZ point that INCOIS no longer lists — remove it.
    private static final String cleanupSql =
            "DELETE FROM pfz_point WHERE fetched_at < ?";

//========================================================================================================================
//                                                    FETCH
//========================================================================================================================

    public static String preFetch(String secid) throws Exception {
        HttpUtils.sendGET(primaryURL + secid, "", "", false);
        Thread.sleep(500);
        HttpUtils.sendGET(secondaryURL + secid, "Referer", primaryURL + secid, false);
        return HttpUtils.sendGET(tertiaryURL, "Referer", secondaryURL + secid, false);
    }


    public static List<PfzPoint> fetch() {
        List<PfzPoint> allPoints = new ArrayList<>();
        int secid = 1;
        while (secid <= 14) {
            String secIdStr = String.format("SEC%03d", secid);
            try {
                String response = preFetch(secIdStr);
                System.out.println(secIdStr + " response length: " + response.length());
                List<PfzPoint> zonePoints = parseBody(response, secIdStr);
                System.out.println(secIdStr + " parsed " + zonePoints.size() + " points");
                allPoints.addAll(zonePoints);
            } catch (Exception e) {
                System.err.println("Failed to fetch " + secIdStr + ": " + e.getMessage());
            }
            secid++;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return allPoints;
    }
    //=====================================================================================================================
//                                                  PARSING
//======================================================================================================================
    public static String extractEnglishName(String raw) {
        // Strip Hindi/Devanagari script text
        String noHindi = DEVANAGARI.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").trim();

        // Collapse "Name Name" (English repeated on both sides of the removed Hindi) into just "Name"
        Matcher m = DUPLICATE_NAME.matcher(noHindi);
        if (m.matches()) {
            return m.group(1).trim();
        }
        return noHindi;
    }

    private static String normalizeDash(String text) {
        return text.replaceAll("\\p{Pd}", "-");
    }

    public static double dmsToDecimal(int degrees, int minutes, int seconds, char hemisphere) {
        double decimal = degrees + (minutes / 60.0) + (seconds / 3600.0);
        if (hemisphere == 'S' || hemisphere == 'W') {
            decimal = -decimal;
        }
        return decimal;
    }



    static List<PfzPoint> parseBody(String responseBody, String secId) {
        Document doc = Jsoup.parse(responseBody);
        Elements rows = doc.select("tr");
        List<PfzPoint> points = new ArrayList<>();

        for (Element row : rows) {
            Elements cells = row.select("td");
            if (cells.isEmpty()) continue;

            try {
                String locationName = extractEnglishName(cells.get(0).text().trim());
                String direction = cells.get(1).text().trim();
                int bearing = Integer.parseInt(cells.get(2).text().trim());

                String[] distRange = normalizeDash(cells.get(3).text().trim()).split("-");
                double distMinKm = Double.parseDouble(distRange[0].trim());
                double distMaxKm = Double.parseDouble(distRange[1].trim());

                String[] depthRange = normalizeDash(cells.get(4).text().trim()).split("-");
                double depthMinM = Double.parseDouble(depthRange[0].trim());
                double depthMaxM = Double.parseDouble(depthRange[1].trim());

                double latitude = parseDms(cells.get(5).text().trim());
                double longitude = parseDms(cells.get(6).text().trim());

                points.add(new PfzPoint(locationName, direction, bearing,
                        distMinKm, distMaxKm, depthMinM, depthMaxM, latitude, longitude, secId));
            } catch (Exception e) {
                System.err.println("Skipped row: " + row.text() + " — " + e.getMessage());
            }
        }
        return points;
    }

    private static double parseDms(String dmsText) {
        String[] parts = dmsText.split("\\s+");
        int deg = Integer.parseInt(parts[0]);
        int min = Integer.parseInt(parts[1]);
        int sec = Integer.parseInt(parts[2]);
        char hem = parts[3].charAt(0);
        return dmsToDecimal(deg, min, sec, hem);
    }


    // ========================================================================================================================
//                                                    UPDATE
//========================================================================================================================
    public static int store(List<PfzPoint> points) throws Exception {
        int successCount = 0;

        // Captured before any upserts run in this call. After all of today's points
        // are upserted (each stamped fetched_at = now()), anything still older than
        // this timestamp was NOT present in today's fetch — it's a stale zone INCOIS
        // no longer lists, and gets removed below.
        Timestamp runStart = Timestamp.from(Instant.now());

        try (Connection conn = DatabaseManager.getConnection()) {

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (PfzPoint point : points) {
                    try {
                        ps.setString(1, point.secId());
                        ps.setString(2, point.locationName());
                        ps.setString(3, point.direction());
                        ps.setInt(4, point.bearing());
                        ps.setDouble(5, point.distMinKm());
                        ps.setDouble(6, point.distMaxKm());
                        ps.setDouble(7, point.depthMinM());
                        ps.setDouble(8, point.depthMaxM());
                        ps.setDouble(9, point.latitude());
                        ps.setDouble(10, point.longitude());
                        ps.executeUpdate();
                        successCount++;
                    } catch (Exception e) {
                        System.err.println("Skipped insert for " + point.locationName() + ": " + e.getMessage());
                    }
                }
            }

            // Only run cleanup if the fetch actually returned something — if fetch()
            // failed entirely and points is empty/small, this guards against wiping
            // the whole table on a bad run (e.g. INCOIS temporarily down).
            if (!points.isEmpty()) {
                try (PreparedStatement cleanup = conn.prepareStatement(cleanupSql)) {
                    cleanup.setTimestamp(1, runStart);
                    int removed = cleanup.executeUpdate();
                    if (removed > 0) {
                        System.out.println("Removed " + removed + " stale pfz_point row(s) not present in this fetch.");
                    }
                }
            } else {
                System.out.println("Fetch returned no points — skipping stale-row cleanup to avoid wiping the table on a failed run.");
            }
        }

        return successCount;
    }

    //  ========================================================================================================================
//                                                   ORCHESTRATION
//  ========================================================================================================================
    public static void fetchAndStore() {
        int successCount = 0;
        String failureMessage = null;

        try {
            List<PfzPoint> points = fetch();
            successCount = store(points);
        } catch (Exception e) {
            failureMessage = e.getMessage();
            System.out.println("Error: " + failureMessage);
        }
        FetchLogger.log(SOURCE_NAME, successCount, failureMessage);
    }

    public static void main(String[] args) {
        fetchAndStore();
    }
}