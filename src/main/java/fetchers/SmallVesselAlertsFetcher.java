package fetchers;

import com.google.gson.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import fetchers.response.incois.sva.DayAdvisory;
import utils.DatabaseManager;
import utils.HttpUtils;
import java.sql.*;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import static utils.Config.SVAS_ADVISORY_URL;

public class SmallVesselAlertsFetcher {
    // ====================================================================================================
    //                                             CONFIGS
    // ====================================================================================================

    private static final DateTimeFormatter SRC_DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final String[] LANGS =
            {"ENG", "HIN", "BAN", "GUJ", "KAN", "MAL", "MAR", "ODI", "TAM", "TEL"};
    private static final int[] WIDTHS = {4, 6, 7};
    private static final String SOURCE = "INCOIS";

    private static final String upsertDistrict = """
        INSERT INTO svas_districts (fid_coasta, district_name, state, geom)
        VALUES (?, ?, ?, ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)))
        ON CONFLICT (fid_coasta) DO UPDATE SET
            district_name = EXCLUDED.district_name,
            state         = EXCLUDED.state,
            geom          = EXCLUDED.geom
        """;

    private static final String upsertStatus = """
        INSERT INTO svas_advisory_status
            (district_id, boat_width, forecast_day, forecast_date, status_color, fetched_at)
        VALUES (?, ?, ?, ?, ?, now())
        ON CONFLICT (district_id, boat_width, forecast_day) DO UPDATE SET
            forecast_date = EXCLUDED.forecast_date,
            status_color  = EXCLUDED.status_color,
            fetched_at    = now()
        RETURNING id
        """;

    private static final String upsertText = """
        INSERT INTO svas_advisory_text (advisory_status_id, language_code, advisory_text)
        VALUES (?, ?, ?)
        ON CONFLICT (advisory_status_id, language_code) DO UPDATE SET
            advisory_text = EXCLUDED.advisory_text
        """;

    // ====================================================================================================
    //                                               FETCH
    // ====================================================================================================
    public static String fetch() {
        return HttpUtils.sendGET(SVAS_ADVISORY_URL, "Accept-encoding", "identity", false);
    }

    // ====================================================================================================
    //                                             PARSING
    // ====================================================================================================
    /**
     * Splits raw HTML by day headers, cleans HTML artifacts (&emsp;, EMSPEM),
     * strips trailing next-day headers, and extracts daily advisory data.
     */
    static List<DayAdvisory> parseDayBlocks(String html) {
        List<DayAdvisory> days = new ArrayList<>();
        if (html == null || html.isBlank()) return days;

        // Split raw HTML by day pattern lookahead to preserve tag structure per block
        String[] slices = html.split("(?=\\(\\d{2}-\\d{2}-\\d{4}\\):)");
        Pattern datePattern = Pattern.compile("\\((\\d{2}-\\d{2}-\\d{4})\\):\\s*(.*)", Pattern.DOTALL);

        int dayNum = 1;
        for (String slice : slices) {
            if (slice.isBlank()) continue;

            Document doc = Jsoup.parse(slice);
            String cleanText = doc.text().trim();
            Matcher m = datePattern.matcher(cleanText);

            if (m.find()) {
                String date = m.group(1).trim();
                String rawText = m.group(2).trim();

                // 1. Strip trailing day header labels (e.g., "Day-2", "Day-3") left at the end of slice
                // 2. Remove em-space / EMSPEM entity artifacts and collapse extra spaces
                String text = rawText
                        .replaceAll("(?i)(&emsp;|emsp|EMSPEM|\\u2003|Day\\s*-?\\s*\\d+)+$", "")
                        .replaceAll("(?i)&emsp;|emsp|EMSPEM|\\u2003", " ")
                        .replaceAll("\\s+", " ")
                        .trim();

                // Check hex color strictly within this day's HTML slice
                String status = slice.toUpperCase().contains("#E97132") ? "alert" : "safe";
                days.add(new DayAdvisory(dayNum++, date, status, text));
            }
        }
        return days;
    }

    // ====================================================================================================
    //                                              UPDATE
    // ====================================================================================================
    public static void store(Connection conn, String rawJson) throws Exception {
        JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
        JsonArray features = root.getAsJsonArray("features");

        try (PreparedStatement psDistrict = conn.prepareStatement(upsertDistrict);
             PreparedStatement psStatus = conn.prepareStatement(upsertStatus);
             PreparedStatement psText = conn.prepareStatement(upsertText)) {

            int districtCount = 0, statusCount = 0, textCount = 0, skippedWidths = 0;

            for (JsonElement fe : features) {
                JsonObject feature = fe.getAsJsonObject();
                JsonObject props = feature.getAsJsonObject("properties");
                JsonObject geometry = feature.getAsJsonObject("geometry");

                int fid = props.get("FID_Coasta").getAsInt();
                String district = props.get("DistrictNa").getAsString();
                String state = props.get("state").getAsString();

                // 1) Upsert district
                psDistrict.setInt(1, fid);
                psDistrict.setString(2, district);
                psDistrict.setString(3, state);
                psDistrict.setString(4, geometry.toString());
                psDistrict.executeUpdate();
                districtCount++;

                // 2) Parse all language blocks per boat width
                for (int w : WIDTHS) {
                    Map<String, List<DayAdvisory>> byLang = new HashMap<>();
                    for (String lang : LANGS) {
                        String key = lang + w;
                        if (props.has(key) && !props.get(key).isJsonNull()) {
                            byLang.put(lang, parseDayBlocks(props.get(key).getAsString()));
                        }
                    }

                    // Prefer ENG as canonical source; fallback to any available language payload
                    List<DayAdvisory> canonical = byLang.get("ENG");
                    if (canonical == null || canonical.isEmpty()) {
                        canonical = byLang.values().stream()
                                .filter(list -> list != null && !list.isEmpty())
                                .findFirst()
                                .orElse(null);
                    }

                    if (canonical == null || canonical.isEmpty()) {
                        skippedWidths++;
                        continue;
                    }

                    // 3) Upsert status per forecast day
                    for (DayAdvisory day : canonical) {
                        LocalDate parsedDate;
                        try {
                            parsedDate = LocalDate.parse(day.date(), SRC_DATE_FMT);
                        } catch (Exception ex) {
                            System.err.println("Skipping unparseable date '" + day.date()
                                    + "' for district " + fid + " width " + w);
                            continue;
                        }

                        psStatus.setInt(1, fid);
                        psStatus.setInt(2, w);
                        psStatus.setInt(3, day.day());
                        psStatus.setDate(4, Date.valueOf(parsedDate));
                        psStatus.setString(5, day.status());

                        long statusId;
                        try (ResultSet rs = psStatus.executeQuery()) {
                            if (!rs.next()) continue;
                            statusId = rs.getLong("id");
                        }
                        statusCount++;

                        // 4) Batch insert advisory text across all available languages
                        int batchSize = 0;
                        for (Map.Entry<String, List<DayAdvisory>> entry : byLang.entrySet()) {
                            String lang = entry.getKey();
                            List<DayAdvisory> langDays = entry.getValue();
                            DayAdvisory matching = langDays.stream()
                                    .filter(d -> d.day() == day.day())
                                    .findFirst()
                                    .orElse(null);

                            if (matching == null || matching.text().isBlank()) continue;

                            psText.setLong(1, statusId);
                            psText.setString(2, lang);
                            psText.setString(3, matching.text());
                            psText.addBatch();
                            batchSize++;
                            textCount++;
                        }
                        if (batchSize > 0) {
                            psText.executeBatch();
                        }
                    }
                }
            }

            System.out.printf(
                    "Ingested: %d districts, %d status rows, %d text rows (%d width-blocks skipped)%n",
                    districtCount, statusCount, textCount, skippedWidths);
        }
    }

    // ====================================================================================================
    //                                              ORCHESTRATION
    // ====================================================================================================
    public static void fetchAndStore() throws Exception {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                store(conn, fetch());
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        fetchAndStore();
    }
}