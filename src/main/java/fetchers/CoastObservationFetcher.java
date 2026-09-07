package fetchers;

import com.google.gson.Gson;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import fetchers.response.imd.stations.MarineStation;
import fetchers.response.imd.stations.StationObservation;
import utils.DatabaseManager;
import utils.FetchLogger;
import utils.HttpUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CoastObservationFetcher {

    private static final String SOURCE_NAME = "CoastalObservationScout";
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Gson GSON = new Gson();
    private static final String MAP_PAGE_URL =
            "https://mausam.imd.gov.in/responsive/coastal_observations.php";
    private static final String BASE_DETAIL_URL =
            "https://mausam.imd.gov.in/imd_latest/contents/";
    private static final DateTimeFormatter OBS_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm");

    private static final double MIN_LAT = 5.0;
    private static final double MAX_LAT = 25.0;
    private static final double MIN_LON = 65.0;
    private static final double MAX_LON = 95.0;

    // ---------- parsing helpers ----------

    static Double parseNumber(String raw) {
        if (raw == null) return null;
        Matcher m = NUMBER_PATTERN.matcher(raw);
        return m.find() ? Double.parseDouble(m.group()) : null;
    }

    static String parseUnit(String raw) {
        if (raw == null) return null;
        String noNumber = raw.replaceAll("-?\\d+(\\.\\d+)?", "").trim();
        return noNumber.isEmpty() ? null : noNumber;
    }

    /** "Westerly 3.7 Km/Hr" -> direction="Westerly", speed=3.7, unit="Km/Hr" (coastal station format) */
    record WindCombined(String direction, Double speed, String unit) {}

    static WindCombined parseCombinedWind(String raw) {
        if (raw == null) return new WindCombined(null, null, null);
        Matcher numMatch = NUMBER_PATTERN.matcher(raw);
        if (!numMatch.find()) {
            return new WindCombined(raw.trim(), null, null); // no number found, treat whole thing as direction
        }
        String direction = raw.substring(0, numMatch.start()).trim();
        Double speed = Double.parseDouble(numMatch.group());
        String unit = raw.substring(numMatch.end()).trim();
        return new WindCombined(direction.isEmpty() ? null : direction, speed, unit.isEmpty() ? null : unit);
    }

    static double[] parseLatLon(String raw) {
        if (raw == null) return new double[]{Double.NaN, Double.NaN};
        String[] parts = raw.split(",");
        if (parts.length != 2) return new double[]{Double.NaN, Double.NaN};
        try {
            return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (NumberFormatException e) {
            return new double[]{Double.NaN, Double.NaN};
        }
    }

    static LocalDateTime parseObservedAt(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("\\s*IST\\s*$", "").trim(); // strip trailing "IST"
        try {
            return LocalDateTime.parse(cleaned, OBS_DATE_FORMAT);
        } catch (Exception e) {
            System.err.println("Could not parse observed_at: " + raw);
            return null;
        }
    }

    /** Tries several possible label variants for the same field, since source labels aren't perfectly consistent. */
    static String getField(Map<String, String> fields, String... candidates) {
        for (String key : candidates) {
            if (fields.containsKey(key)) return fields.get(key);
        }
        return null;
    }

    // ---------- store ----------

    public static int store(List<StationObservation> observations) throws Exception {
        int successCount = 0;

        String sql = "INSERT INTO marine_observations " +
                "(station_id, station_type, station_name, observed_at, latitude, longitude, " +
                "pressure_hpa, air_temp_c, dew_point_c, humidity_pct, wind_direction, wind_speed, wind_speed_unit, wind_gust, " +
                "visibility_m, sky_condition, sst_c, wave_direction, wave_period_sec, sig_wave_height_m, max_wave_height_m, " +
                "raw_fields, fetched_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now()) " +
                "ON CONFLICT (station_id, observed_at) DO UPDATE SET " +
                "pressure_hpa = EXCLUDED.pressure_hpa, air_temp_c = EXCLUDED.air_temp_c, " +
                "humidity_pct = EXCLUDED.humidity_pct, sst_c = EXCLUDED.sst_c, fetched_at = now()";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (StationObservation obs : observations) {
                try {
                    Map<String, String> f = obs.fields();

                    double[] latLon = parseLatLon(getField(f, "Location ( Lat, Long )", "Location (Lat, Long)"));
                    LocalDateTime observedAt = parseObservedAt(getField(f, "Date & Time of Observation"));

                    Double pressure = parseNumber(getField(f, "MSL Pressure", "M S L Pressure"));
                    Double airTemp = parseNumber(getField(f, "Air Temperature", "Current Temperature"));
                    Double dewPoint = parseNumber(getField(f, "Dew Point Temperature"));
                    Double humidity = parseNumber(getField(f, "Humidity"));
                    Double sst = parseNumber(getField(f, "Sea Surface Temperature", "Sea Suface Temperature"));
                    Double visibility = parseNumber(getField(f, "Visibility", "Horizontal Visibility"));
                    String skyCondition = getField(f, "Sky Condition", "Weather");

                    // Wind: ships/buoys have separate Direction/Speed fields; coastal stations combine them
                    String windDirRaw = getField(f, "Wind Direction");
                    String windSpeedRaw = getField(f, "Wind Speed");
                    String windCombinedRaw = getField(f, "Wind Direction & Speed");

                    String windDirection;
                    Double windSpeed;
                    String windSpeedUnit;

                    if (windCombinedRaw != null) {
                        WindCombined wc = parseCombinedWind(windCombinedRaw);
                        windDirection = wc.direction();
                        windSpeed = wc.speed();
                        windSpeedUnit = wc.unit();
                    } else {
                        windDirection = windDirRaw;
                        windSpeed = parseNumber(windSpeedRaw);
                        windSpeedUnit = parseUnit(windSpeedRaw); // e.g. "KT"
                    }

                    Double windGust = parseNumber(getField(f, "Wind Gust"));
                    String waveDirection = getField(f, "Wave Direction");
                    Double wavePeriod = parseNumber(getField(f, "Wave Period"));
                    Double sigWaveHeight = parseNumber(getField(f, "Significant Wave Height"));
                    Double maxWaveHeight = parseNumber(getField(f, "Max Wave Height"));

                    ps.setString(1, obs.stationId());
                    ps.setString(2, obs.stationType());
                    ps.setString(3, obs.stationName());
                    if (observedAt != null) ps.setTimestamp(4, Timestamp.valueOf(observedAt));
                    else ps.setNull(4, Types.TIMESTAMP);
                    ps.setDouble(5, latLon[0]);
                    ps.setDouble(6, latLon[1]);
                    setNullableDouble(ps, 7, pressure);
                    setNullableDouble(ps, 8, airTemp);
                    setNullableDouble(ps, 9, dewPoint);
                    setNullableDouble(ps, 10, humidity);
                    ps.setString(11, windDirection);
                    setNullableDouble(ps, 12, windSpeed);
                    ps.setString(13, windSpeedUnit);
                    setNullableDouble(ps, 14, windGust);
                    setNullableDouble(ps, 15, visibility);
                    ps.setString(16, skyCondition);
                    setNullableDouble(ps, 17, sst);
                    ps.setString(18, waveDirection);
                    setNullableDouble(ps, 19, wavePeriod);
                    setNullableDouble(ps, 20, sigWaveHeight);
                    setNullableDouble(ps, 21, maxWaveHeight);
                    ps.setString(22, GSON.toJson(f));

                    ps.executeUpdate();
                    successCount++;
                } catch (Exception e) {
                    System.err.println("Skipped insert for " + obs.stationId() + ": " + e.getMessage());
                }
            }
        }
        return successCount;
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value) throws Exception {
        if (value != null) ps.setDouble(index, value);
        else ps.setNull(index, Types.DOUBLE);
    }

    // ---------- orchestration ----------

    public static void fetchAndStore() {
        int successCount = 0;
        String failureMessage = null;

        try {
            List<MarineStation> allStations = fetchAllMarineStations();
            List<MarineStation> indianStations = filterToIndianWaters(allStations);

            List<StationObservation> observations = new ArrayList<>();
            for (MarineStation s : indianStations) {
                try {
                    observations.add(fetchStationObservation(s));
                    Thread.sleep(300); // be polite to IMD's server
                } catch (Exception e) {
                    System.err.println("Failed " + s.stationType() + " id=" + s.stationId() + ": " + e.getMessage());
                }
            }

            successCount = store(observations);
        } catch (Exception e) {
            failureMessage = e.getMessage();
            System.out.println("Error: " + failureMessage);
        }

        FetchLogger.log(SOURCE_NAME, successCount, failureMessage);
    }
    static List<MarineStation> fetchAllMarineStations() throws Exception {
        String html = HttpUtils.sendGET(MAP_PAGE_URL, "Accept", "text/html", false);
        List<MarineStation> stations = new ArrayList<>();

        Pattern pattern = Pattern.compile(
                "L\\.marker\\(\\[([\\d.\\-]+),\\s*([\\d.\\-]+)\\].*?(coastal_stn_obs|coastal_ship_obs|buoy_obs)\\.php\\?id=([\\w]+)",
                Pattern.DOTALL
        );

        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            double lat = Double.parseDouble(matcher.group(1));
            double lon = Double.parseDouble(matcher.group(2));
            String type = matcher.group(3);
            String id = matcher.group(4);
            stations.add(new MarineStation(lat, lon, id, type));
        }
        return stations;
    }

    static List<MarineStation> filterToIndianWaters(List<MarineStation> all) {
        return all.stream()
                .filter(s -> s.lat() >= MIN_LAT && s.lat() <= MAX_LAT
                        && s.lon() >= MIN_LON && s.lon() <= MAX_LON)
                .toList();
    }

    static String detailUrlFor(MarineStation station) {
        String file = switch (station.stationType()) {
            case "coastal_stn_obs" -> "coastal_stn_obs.php?id=";
            case "coastal_ship_obs" -> "coastal_ship_obs.php?id=";
            case "buoy_obs" -> "buoy_obs.php?id=";
            default -> throw new IllegalArgumentException("Unknown station type: " + station.stationType());
        };
        return BASE_DETAIL_URL + file + station.stationId();
    }

    static StationObservation fetchStationObservation(MarineStation station) throws Exception {
        String url = detailUrlFor(station);
        String html = HttpUtils.sendGET(url, "Accept", "text/html", true);
        Document doc = Jsoup.parse(html);

        Map<String, String> fields = new HashMap<>();
        Elements rows = doc.select("tr");
        for (Element row : rows) {
            Elements cells = row.select("td");
            if (cells.size() >= 2) {
                fields.put(cells.get(0).text().trim(), cells.get(1).text().trim());
            }
        }

        if (fields.isEmpty()) {
            throw new RuntimeException("No observation data found for " + station.stationType() + " id=" + station.stationId());
        }

        String heading = doc.select("h3, h2, b").text();
        return new StationObservation(station.stationId(), station.stationType(), heading, fields);
    }

    public static void main(String[] args) {
        fetchAndStore();
    }
}