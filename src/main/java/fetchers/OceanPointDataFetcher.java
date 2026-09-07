package fetchers;

import ucar.ma2.Array;
import ucar.ma2.Index;
import ucar.nc2.Attribute;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;
import utils.DatabaseManager;
import utils.FetchLogger;
import utils.ThredssLookup;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class OceanPointDataFetcher {

    private static final String SOURCE_NAME = "OsfGridScout";
    private static final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    public record Tile(String name, double north, double south, double east, double west, int horizStride) {
        public Tile(String name, double north, double south, double east, double west) {
            this(name, north, south, east, west, 1); // default stride 1 for coastal tiles
        }
    }
    public record OsfVariable(String label, String datasetPath, List<List<String>> varGroups) {}
    public record GridPoint(String variable, double lat, double lon, double value, LocalDate date, String tileName) {}

    private static final Map<String, String> VAR_NAME_MAP = Map.of(
            "SST", "sst",
            "SWH", "wave_height",
            "SWELL", "swell_height",
            "MAXW", "max_wave_height",
            "MAX", "max_wave_height",
            "MWH", "max_wave_height",
            "mwh", "max_wave_height"
    );

    private static final List<Tile> TILES = List.of(
            // West Coast
            new Tile("gujarat", 24.7, 20.1, 74.5, 68.1),
            new Tile("maharashtra", 22.0, 15.6, 80.9, 72.6),
            new Tile("goa", 15.8, 14.9, 74.3, 73.6),
            new Tile("karnataka", 18.5, 11.6, 78.6, 74.1),
            new Tile("kerala", 12.5, 8.0, 77.5, 74.5),

            // East Coast
            new Tile("tamil_nadu", 14.0, 8.0, 81.0, 77.0),
            new Tile("andhra_pradesh", 19.1, 12.6, 84.8, 76.8),
            new Tile("odisha", 22.6, 17.8, 87.5, 81.4),
            new Tile("west_bengal", 27.2, 21.5, 89.9, 85.8),

            // Union Territory
            new Tile("daman_diu", 20.8, 20.3, 73.0, 70.8),
            new Tile("puducherry", 12.0, 10.8, 79.9, 79.7),
            new Tile("lakshadweep", 12.4, 8.2, 74.0, 71.7),
            new Tile("andaman_nicobar", 13.7, 6.7, 94.0, 92.2),

            // Open-ocean gap fill (EEZ coverage) — coarser stride since these are large,
            // slowly-varying, low-priority regions compared to the coastal tiles above
            new Tile("arabian_sea_offshore", 23.62, 4.79, 78.85, 65.64, 4),
            new Tile("andaman_nicobar_extended", 15.72, 3.84, 95.70, 88.80, 3),
            new Tile("bay_of_bengal_offshore_north", 21.5, 14.24, 89.37, 84.80, 3),
            new Tile("bay_of_bengal_offshore_south", 12.6, 10.67, 83.69, 81.00, 3)
    );

    private static final List<OsfVariable> VARIABLES = List.of(
            new OsfVariable("sst", "osf/sst", List.of(
                    List.of("SST")
            )),
            new OsfVariable("winds", "osf/winds", List.of(
                    List.of("WSM", "WSXM", "WSYM"),
                    List.of("WSM")
            )),
            new OsfVariable("wave", "osf/wave", List.of(
                    List.of("SWH")
            )),
            new OsfVariable("current", "osf/currents", List.of(
                    List.of("CURRENT", "U", "V")
            )),
            new OsfVariable("mwh", "osf/mwh", List.of(
                    List.of("MAXW")
            ))
    );

    static String buildNcssUrl(Tile tile, String datasetPath, String datasetFileName, List<String> varNames) {
        StringBuilder varParams = new StringBuilder();
        for (String v : varNames) {
            varParams.append("var=").append(v).append("&");
        }

        return String.format(
                "https://incois.gov.in/thredds/ncss/grid/%s/%s?%snorth=%.4f&west=%.4f&east=%.4f&south=%.4f&horizStride=%d&temporal=all&accept=netcdf3",
                datasetPath, datasetFileName, varParams,
                tile.north(), tile.west(), tile.east(), tile.south(), tile.horizStride()
        );
    }

    static List<GridPoint> fetchOne(Tile tile, OsfVariable var, String datasetFileName) {
        for (List<String> group : var.varGroups()) {
            String url = buildNcssUrl(tile, var.datasetPath(), datasetFileName, group);
            String tempFile = "temp_" + UUID.randomUUID() + ".nc";

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "Mozilla/5.0 (GLAUCUS-ORCA research project)")
                        .GET().build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

                System.out.println("  [" + var.label() + "] group " + group + " -> HTTP " + response.statusCode());

                if (response.statusCode() == 200) {
                    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                        fos.write(response.body());
                    }
                    List<GridPoint> points = parseNetcdf(tempFile, var, tile);
                    System.out.println("  [" + var.label() + "] parsed " + points.size() + " points from group " + group);
                    if (!points.isEmpty()) {
                        return points;
                    }
                } else {
                    String snippet = new String(response.body());
                    System.out.println("  [" + var.label() + "] response body: " + snippet.substring(0, Math.min(200, snippet.length())));
                }
            } catch (Exception e) {
                System.out.println("  [" + var.label() + "] group " + group + " threw: " + e.getMessage());
            } finally {
                File f = new File(tempFile);
                if (f.exists()) f.delete();
            }
        }
        return List.of();
    }

    private static Variable findLatitudeVariable(NetcdfFile ncFile) {
        for (Variable v : ncFile.getVariables()) {
            Attribute typeAttr = v.findAttribute("_CoordinateAxisType");
            if (typeAttr != null && "Lat".equalsIgnoreCase(typeAttr.getStringValue())) return v;

            Attribute unitsAttr = v.findAttribute("units");
            if (unitsAttr != null && unitsAttr.getStringValue().toLowerCase().contains("degrees_north")) return v;

            Attribute stdAttr = v.findAttribute("standard_name");
            if (stdAttr != null && "latitude".equalsIgnoreCase(stdAttr.getStringValue())) return v;
        }
        for (Variable v : ncFile.getVariables()) {
            String name = v.getShortName().toLowerCase();
            if (name.contains("lat") || name.equals("ax005")) return v;
        }
        return null;
    }

    private static Variable findLongitudeVariable(NetcdfFile ncFile) {
        for (Variable v : ncFile.getVariables()) {
            Attribute typeAttr = v.findAttribute("_CoordinateAxisType");
            if (typeAttr != null && "Lon".equalsIgnoreCase(typeAttr.getStringValue())) return v;

            Attribute unitsAttr = v.findAttribute("units");
            if (unitsAttr != null && unitsAttr.getStringValue().toLowerCase().contains("degrees_east")) return v;

            Attribute stdAttr = v.findAttribute("standard_name");
            if (stdAttr != null && "longitude".equalsIgnoreCase(stdAttr.getStringValue())) return v;
        }
        for (Variable v : ncFile.getVariables()) {
            String name = v.getShortName().toLowerCase();
            if (name.contains("lon") || name.equals("ax004")) return v;
        }
        return null;
    }

    private static Variable findVariableIgnoreCase(NetcdfFile ncFile, String... candidates) {
        for (String candidate : candidates) {
            Variable v = ncFile.findVariable(candidate);
            if (v != null) return v;
            for (Variable var : ncFile.getVariables()) {
                if (var.getShortName().equalsIgnoreCase(candidate)) return var;
            }
        }
        return null;
    }

    private static double getAttributeDouble(Variable v, String attrName, double defaultValue) {
        Attribute attr = v.findAttribute(attrName);
        return (attr != null && attr.getNumericValue() != null) ? attr.getNumericValue().doubleValue() : defaultValue;
    }

    private static Double getAttributeDoubleNullable(Variable v, String attrName) {
        Attribute attr = v.findAttribute(attrName);
        return (attr != null && attr.getNumericValue() != null) ? attr.getNumericValue().doubleValue() : null;
    }

    private static double getUnpackedValue(Variable var, Array array, int latIdx, int lonIdx) {
        Index idx = array.getIndex();
        switch (array.getRank()) {
            case 4 -> idx.set(0, 0, latIdx, lonIdx);
            case 3 -> idx.set(0, latIdx, lonIdx);
            case 2 -> idx.set(latIdx, lonIdx);
            default -> { return Double.NaN; }
        }

        double rawVal = array.getDouble(idx);
        Double fillValue = getAttributeDoubleNullable(var, "_FillValue");

        boolean isFill = Double.isNaN(rawVal) || Double.isInfinite(rawVal) ||
                rawVal <= -1e30 || rawVal >= 1e30 ||
                (fillValue != null && Math.abs(rawVal - fillValue) < 1e-2) ||
                rawVal == -9999.0 || rawVal == -999.0;

        if (isFill) return Double.NaN;

        double scaleFactor = getAttributeDouble(var, "scale_factor", 1.0);
        double addOffset = getAttributeDouble(var, "add_offset", 0.0);
        return (rawVal * scaleFactor) + addOffset;
    }

    private static boolean isValidValue(double val) {
        return !Double.isNaN(val) && !Double.isInfinite(val);
    }

    static List<GridPoint> parseNetcdf(String path, OsfVariable var, Tile tile) throws Exception {
        List<GridPoint> points = new ArrayList<>();

        try (NetcdfFile ncFile = NetcdfFile.open(path)) {
            Variable latVar = findLatitudeVariable(ncFile);
            Variable lonVar = findLongitudeVariable(ncFile);

            if (latVar == null || lonVar == null) return points;

            Array latData = latVar.read();
            Array lonData = lonVar.read();
            int nLat = latData.getShape()[0];
            int nLon = lonData.getShape()[0];
            LocalDate today = LocalDate.now();

            String label = var.label().toLowerCase();

            if (label.startsWith("wind") || label.startsWith("current")) {
                boolean isWind = label.startsWith("wind");
                String speedLabel = isWind ? "wind_speed" : "current_speed";
                String dirLabel = isWind ? "wind_direction" : "current_direction";

                Variable uVar = findVariableIgnoreCase(ncFile, "WSXM", "u", "u_wind", "ucurrent", "U");
                Variable vVar = findVariableIgnoreCase(ncFile, "WSYM", "v", "v_wind", "vcurrent", "V");
                Variable speedVar = findVariableIgnoreCase(ncFile, "WSM", "speed", "CURRENT", "CURRENTS", "current_speed");

                if (uVar != null && vVar != null) {
                    Array uData = uVar.read();
                    Array vData = vVar.read();
                    Array speedData = (speedVar != null) ? speedVar.read() : null;

                    for (int i = 0; i < nLat; i++) {
                        for (int j = 0; j < nLon; j++) {
                            double lat = latData.getDouble(i);
                            double lon = lonData.getDouble(j);

                            double u = getUnpackedValue(uVar, uData, i, j);
                            double v = getUnpackedValue(vVar, vData, i, j);

                            if (isValidValue(u) && isValidValue(v)) {
                                double speed = (speedVar != null) ? getUnpackedValue(speedVar, speedData, i, j) : Math.hypot(u, v);
                                if (!isValidValue(speed)) {
                                    speed = Math.hypot(u, v);
                                }
                                double direction = (Math.toDegrees(Math.atan2(u, v)) + 360) % 360;

                                if (isValidValue(speed)) {
                                    points.add(new GridPoint(speedLabel, lat, lon, speed, today, tile.name()));
                                }
                                points.add(new GridPoint(dirLabel, lat, lon, direction, today, tile.name()));
                            }
                        }
                    }
                } else if (speedVar != null) {
                    Array speedData = speedVar.read();
                    for (int i = 0; i < nLat; i++) {
                        for (int j = 0; j < nLon; j++) {
                            double lat = latData.getDouble(i);
                            double lon = lonData.getDouble(j);
                            double speed = getUnpackedValue(speedVar, speedData, i, j);
                            if (isValidValue(speed)) {
                                points.add(new GridPoint(speedLabel, lat, lon, speed, today, tile.name()));
                            }
                        }
                    }
                }
            } else {
                for (Variable ncVar : ncFile.getVariables()) {
                    String varName = ncVar.getShortName();
                    String varUpper = varName.toUpperCase();

                    if (varName.equals(latVar.getShortName()) || varName.equals(lonVar.getShortName()) ||
                            varUpper.contains("TIME") || varUpper.equals("TAX") || varUpper.contains("BOUNDS") ||
                            varUpper.contains("CRS") || varUpper.equals("X") || varUpper.equals("Y")) {
                        continue;
                    }

                    String dbVarLabel = VAR_NAME_MAP.getOrDefault(varName, varName.toLowerCase());
                    Array data = ncVar.read();

                    for (int i = 0; i < nLat; i++) {
                        for (int j = 0; j < nLon; j++) {
                            double lat = latData.getDouble(i);
                            double lon = lonData.getDouble(j);
                            double value = getUnpackedValue(ncVar, data, i, j);

                            if (isValidValue(value)) {
                                points.add(new GridPoint(dbVarLabel, lat, lon, value, today, tile.name()));
                            }
                        }
                    }
                }
            }
        }
        return points;
    }

    public static List<GridPoint> fetch() {
        List<GridPoint> allPoints = new ArrayList<>();

        for (OsfVariable var : VARIABLES) {
            String latestUrlPath;
            try {
                String catalogUrl = "https://incois.gov.in/thredds/catalog/" + var.datasetPath() + "/catalog.xml";
                latestUrlPath = ThredssLookup.findLatestUrlPath(catalogUrl);
            } catch (Exception e) {
                System.err.println("Could not find latest file for " + var.label() + ": " + e.getMessage());
                continue;
            }

            String fileName = latestUrlPath.substring(latestUrlPath.lastIndexOf('/') + 1);

            for (Tile tile : TILES) {
                try {
                    List<GridPoint> points = fetchOne(tile, var, fileName);
                    allPoints.addAll(points);
                    System.out.println(tile.name() + "/" + var.label() + " (" + fileName + "): " + points.size() + " points");
                } catch (Exception e) {
                    System.err.println("Failed " + tile.name() + "/" + var.label() + ": " + e.getMessage());
                }
            }
        }
        return allPoints;
    }

    /**
     * Loads all fetched points into a session-scoped temp table, dedupes rows that
     * collide on the same natural key (this happens where tiles intentionally
     * overlap, e.g. andaman_nicobar / andaman_nicobar_extended), then does a single
     * bulk spatial join against india_eez_polygon to filter out anything outside
     * India's actual EEZ, before upserting the survivors into ocean_point_readings.
     */
    public static int store(List<GridPoint> points) throws Exception {
        int successCount = 0;

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement createTemp = conn.prepareStatement(
                    "CREATE TEMP TABLE temp_grid_points (" +
                            "  variable TEXT," +
                            "  latitude DOUBLE PRECISION," +
                            "  longitude DOUBLE PRECISION," +
                            "  value DOUBLE PRECISION," +
                            "  data_date DATE," +
                            "  tile_name TEXT" +
                            ") ON COMMIT DROP")) {
                createTemp.execute();
            }

            String insertTempSql = "INSERT INTO " +
                    "temp_grid_points " +
                    "(variable, latitude, longitude, value, data_date, tile_name) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertTempSql)) {
                int batchSize = 0;
                for (GridPoint point : points) {
                    ps.setString(1, point.variable());
                    ps.setDouble(2, point.lat());
                    ps.setDouble(3, point.lon());
                    ps.setDouble(4, point.value());
                    ps.setObject(5, point.date());
                    ps.setString(6, point.tileName());
                    ps.addBatch();

                    if (++batchSize % 1000 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }

            // Dedupe rows that share the same natural key — happens where tiles
            // deliberately overlap (e.g. andaman_nicobar vs andaman_nicobar_extended).
            // Keeps one arbitrary row per key; without this the final upsert fails
            // with "ON CONFLICT DO UPDATE command cannot affect row a second time".
            try (PreparedStatement dedupe = conn.prepareStatement("""
                    DELETE FROM temp_grid_points a USING temp_grid_points b
                    WHERE a.ctid < b.ctid
                      AND a.variable = b.variable
                      AND a.latitude = b.latitude
                      AND a.longitude = b.longitude
                      AND a.data_date = b.data_date
                    """)) {
                dedupe.execute();
            }

            // Single bulk spatial join: only points that fall inside the real India
            // EEZ (mainland+Lakshadweep OR Andaman & Nicobar) get upserted.
            String upsertSql = """
                INSERT INTO ocean_point_readings
                    (variable, latitude, longitude, value, data_date, source_tier, tile_name, fetched_at)
                SELECT t.variable, t.latitude, t.longitude, t.value, t.data_date,
                       'incois_thredds', t.tile_name, now()
                FROM temp_grid_points t
                WHERE EXISTS (
                    SELECT 1 FROM india_eez_polygon e
                    WHERE ST_Contains(e.geom, ST_SetSRID(ST_MakePoint(t.longitude, t.latitude), 4326))
                )
                ON CONFLICT (variable, latitude, longitude, data_date) DO UPDATE SET
                    value = EXCLUDED.value,
                    source_tier = EXCLUDED.source_tier,
                    tile_name = EXCLUDED.tile_name,
                    fetched_at = now()
                """;

            try (PreparedStatement upsertStmt = conn.prepareStatement(upsertSql)) {
                successCount = upsertStmt.executeUpdate();
            }

            conn.commit();
        }
        return successCount;
    }

    public static void fetchAndStore() {
        int successCount = 0;
        String failureMessage = null;

        try {
            List<GridPoint> points = fetch();
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
