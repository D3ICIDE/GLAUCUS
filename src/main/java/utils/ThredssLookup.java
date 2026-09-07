package utils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ThredssLookup {

    private static final HttpClient client = HttpClient.newHttpClient();

    /**
     * Fetches a THREDDS sub-catalog and returns the urlPath of the most recently modified dataset.
     * e.g. catalogUrl = "https://incois.gov.in/thredds/catalog/osf/sst/catalog.xml"
     * returns something like "osf/sst/SST_NIO_20260830.nc"
     */
    public static String findLatestUrlPath(String catalogUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(catalogUrl))
                .header("User-Agent", "Mozilla/5.0 (GLAUCUS-ORCA research project)")
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Catalog fetch failed: HTTP " + response.statusCode());
        }

        String xml = response.body();

        // Each <dataset ... urlPath="...">...<date type="modified">...</date></dataset>
        Pattern datasetPattern = Pattern.compile(
                "<dataset\\s+name=\"[^\"]*\"\\s+ID=\"[^\"]*\"\\s+urlPath=\"([^\"]+)\">" +
                        "(?:(?!</dataset>).)*?" +
                        "<date type=\"modified\">([^<]+)</date>",
                Pattern.DOTALL
        );

        Matcher matcher = datasetPattern.matcher(xml);

        String latestUrlPath = null;
        Instant latestTime = Instant.MIN;

        while (matcher.find()) {
            String urlPath = matcher.group(1);
            String dateStr = matcher.group(2);
            Instant modified = Instant.parse(dateStr); // expects ISO-8601, e.g. 2026-08-31T03:15:54.669Z

            if (modified.isAfter(latestTime)) {
                latestTime = modified;
                latestUrlPath = urlPath;
            }
        }

        if (latestUrlPath == null) {
            throw new RuntimeException("No datasets found in catalog: " + catalogUrl);
        }

        return latestUrlPath;
    }

    public static void main(String[] args) throws Exception {
        String latest = findLatestUrlPath("https://incois.gov.in/thredds/catalog/osf/winds/catalog.xml");
        System.out.println("Latest SST file: " + latest);
    }
}