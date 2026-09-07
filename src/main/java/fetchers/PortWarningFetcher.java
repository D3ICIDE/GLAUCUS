package fetchers;

import fetchers.response.imd.ports.PortWarning;
import utils.DatabaseManager;
import utils.FetchLogger;
import utils.HttpUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static utils.Config.PORT_WARNING_URL;

public class PortWarningFetcher {
//    ====================================================================================================
//                                             CONFIGS
//    ====================================================================================================
    private static final String SOURCE_NAME = "PortWarningScout";
    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private static final String sql = """
    INSERT INTO port_warnings
        (port_name, latitude, longitude, warning_level, message, highlighted, bulletin_file, issued_at, fetched_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
    ON CONFLICT (port_name) DO UPDATE SET
        latitude = EXCLUDED.latitude,
        longitude = EXCLUDED.longitude,
        warning_level = EXCLUDED.warning_level,
        message = EXCLUDED.message,
        highlighted = EXCLUDED.highlighted,
        bulletin_file = EXCLUDED.bulletin_file,
        issued_at = EXCLUDED.issued_at,
        fetched_at = now()
    """;


    //====================================================================================================
//                                              FETCH
//    ====================================================================================================
    public static List<PortWarning> fetch() throws Exception {
        String html = HttpUtils.sendGET(PORT_WARNING_URL, "", "", false);
        return parsePortArrays(html);
    }
//    ======================================================================================================
//                                              PARSING
//    ======================================================================================================
    static List<String> extractArray(String html, String varName) {
        List<String> values = new ArrayList<>();
        Pattern pattern = Pattern.compile("var\\s+" + varName + "\\s*=\\s*\\[(.*?)];", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            String arrayContent = matcher.group(1);
            Matcher itemMatcher = Pattern.compile("\"(.*?)\"").matcher(arrayContent);
            while (itemMatcher.find()) {
                values.add(itemMatcher.group(1));
            }
        }
        return values;
    }

    static List<PortWarning> parsePortArrays(String html) {
        List<String> names = extractArray(html, "name_array");
        List<String> lats = extractArray(html, "latitude_array");
        List<String> lons = extractArray(html, "longtitude_array");
        List<String> messages = extractArray(html, "message_array");
        List<String> highlights = extractArray(html, "highlight_port_array");
        List<String> warnings = extractArray(html, "warning_array");
        List<String> files = extractArray(html, "file_array");
        List<String> dates = extractArray(html, "date_array");

        System.out.println("names=" + names.size() + " lats=" + lats.size() + " lons=" + lons.size()
                + " messages=" + messages.size() + " highlights=" + highlights.size()
                + " warnings=" + warnings.size() + " files=" + files.size() + " dates=" + dates.size());

        List<PortWarning> ports = new ArrayList<>();
        int n = names.size();
        for (int i = 0; i < n; i++) {
            try {
                ports.add(new PortWarning(
                        names.get(i),
                        Double.parseDouble(lats.get(i)),
                        Double.parseDouble(lons.get(i)),
                        warnings.get(i),
                        messages.get(i),
                        "Yes".equalsIgnoreCase(highlights.get(i)),
                        files.get(i),
                        dates.get(i)
                ));
            } catch (Exception e) {
                System.err.println("Skipped index " + i + " due to: " + e.getMessage());
            }
        }
        return ports;
    }


    // =========================================================================================
//                                      UPDATE
//    =========================================================================================
    public static int store(List<PortWarning> ports) throws Exception {
        int successCount = 0;

        try (Connection conn = DatabaseManager.getConnection()) {


            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (PortWarning port : ports) {
                    try {
                        ps.setString(1, port.name());
                        ps.setDouble(2, port.latitude());
                        ps.setDouble(3, port.longitude());
                        ps.setString(4, port.warningLevel());
                        ps.setString(5, port.message());
                        ps.setBoolean(6, port.highlighted());
                        ps.setString(7, port.bulletinFile());
                        ps.setTimestamp(8, Timestamp.valueOf(LocalDateTime.parse(port.date(), DATE_FORMAT)));
                        ps.executeUpdate();
                        successCount++;
                    } catch (Exception e) {
                        System.err.println("Skipped insert for " + port.name() + ": " + e.getMessage());
                    }
                }
            }
        }

        return successCount;
    }
    //=========================================================================================
//                                      ORCHESTRATION
//    =========================================================================================
    public static void fetchAndStore() {
        int successCount = 0;
        String failureMessage = null;

        try {
            List<PortWarning> ports = fetch();
            successCount = store(ports);
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