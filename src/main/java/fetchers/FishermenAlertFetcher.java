package fetchers;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import fetchers.response.imd.fishermen.FishermenWarning;
import utils.DatabaseManager;
import utils.FetchLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static fetchers.PortWarningFetcher.DATE_FORMAT;
import static utils.Config.DEFAULT_USER_AGENT;

public class FishermenAlertFetcher {

    static final public String URL = "https://rsmcnewdelhi.imd.gov.in/fishermen-warning.php";
    private static final String SOURCE_NAME = "FishermenAlertScout";

    // FETCH — pure: HTTP + parse, no DB, returns data
    public static List<FishermenWarning> fetch() throws Exception {
        Document doc = Jsoup.connect(URL)
                .userAgent(DEFAULT_USER_AGENT)
                .timeout(10000)
                .get();

        Elements warningBoxes = doc.select("dd.tipMid");
        List<FishermenWarning> warnings = new ArrayList<>();

        for (Element box : warningBoxes) {
            String region = box.selectFirst("strong") != null ? box.selectFirst("strong").text() : "N/A";

            Element smallEl = box.selectFirst("small");
            String warningMsg = "N/A";
            LocalDateTime warningTime = null;

            if (smallEl != null) {
                List<String> smallTexts = new ArrayList<>();
                for (TextNode tn : smallEl.textNodes()) {
                    String clean = tn.text().trim();
                    if (!clean.isEmpty()) {
                        smallTexts.add(clean);
                    }
                }
                if (!smallTexts.isEmpty()) {
                    warningMsg = smallTexts.get(0);
                }
                if (smallTexts.size() > 1) {
                    try {
                        warningTime = LocalDateTime.parse(smallTexts.get(smallTexts.size() - 1), DATE_FORMAT);
                    } catch (Exception e) {
                        System.err.println("Could not parse warning time: " + smallTexts.get(smallTexts.size() - 1));
                    }
                }
            }

            Element pdfLink = box.selectFirst("a.readDetail");
            String pdfUrl = (pdfLink != null) ? pdfLink.attr("abs:href") : "None";

            List<String> cleanTexts = new ArrayList<>();
            for (TextNode textNode : box.textNodes()) {
                String clean = textNode.text().trim();
                if (!clean.isEmpty()) {
                    cleanTexts.add(clean);
                }
            }
            String authority = cleanTexts.isEmpty() ? "N/A" : cleanTexts.get(0);
            String detailMessage = cleanTexts.size() > 1
                    ? String.join(" ", cleanTexts.subList(1, cleanTexts.size()))
                    : null;

            warnings.add(new FishermenWarning(region, authority, detailMessage, warningMsg, warningTime, pdfUrl));
        }

        return warnings;
    }

    // STORE — pure: takes data, writes to DB, returns count inserted
    public static int store(List<FishermenWarning> warnings) throws Exception {
        int successCount = 0;

        try (Connection conn = DatabaseManager.getConnection()) {
            String sql = "INSERT INTO fishermen_warnings " +
                    "(region, authority, detail_message, warning_level, message, warning_time, latitude, longitude, bulletin_file, fetched_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now()) " +
                    "ON CONFLICT (region) DO UPDATE SET " +
                    "authority = EXCLUDED.authority, " +
                    "detail_message = EXCLUDED.detail_message, " +
                    "warning_level = EXCLUDED.warning_level, " +
                    "message = EXCLUDED.message, " +
                    "warning_time = EXCLUDED.warning_time, " +
                    "latitude = EXCLUDED.latitude, " +
                    "longitude = EXCLUDED.longitude, " +
                    "bulletin_file = EXCLUDED.bulletin_file, " +
                    "fetched_at = now()";
            for (FishermenWarning w : warnings) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    double[] coords = lookupCoordinates(conn, w.region());

                    ps.setString(1, w.region());
                    ps.setString(2, w.authority());
                    ps.setString(3, w.detailMessage());
                    ps.setString(4, w.message());      // warning_level duplicates message for now
                    ps.setString(5, w.message());
                    if (w.warningTime() != null) {
                        ps.setTimestamp(6, Timestamp.valueOf(w.warningTime()));
                    } else {
                        ps.setNull(6, Types.TIMESTAMP);
                    }
                    if (coords != null) {
                        ps.setDouble(7, coords[0]);
                        ps.setDouble(8, coords[1]);
                    } else {
                        ps.setNull(7, Types.DOUBLE);
                        ps.setNull(8, Types.DOUBLE);
                    }
                    ps.setString(9, w.pdfUrl());
                    ps.executeUpdate();
                    successCount++;
                } catch (Exception e) {
                    System.err.println("Skipped insert for region " + w.region() + ": " + e.getMessage());
                }
            }
        }

        return successCount;
    }

    // Placeholder — wire up to your location_lookup table
    private static double[] lookupCoordinates(Connection conn, String regionRaw) throws Exception {
        String normalized = regionRaw.toLowerCase().replaceAll("[^a-z\\s]", "").replaceAll("\\s+", " ").trim();
        String sql = "SELECT latitude, longitude FROM location_lookup WHERE region_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalized);
            var rs = ps.executeQuery();
            if (rs.next()) {
                return new double[]{ rs.getDouble("latitude"), rs.getDouble("longitude") };
            }
        }
        return null;
    }

    // ORCHESTRATION — glues fetch + store + logging together
    public static void fetchAndStore() {
        int successCount = 0;
        String failureMessage = null;

        try {
            List<FishermenWarning> warnings = fetch();
            successCount = store(warnings);
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