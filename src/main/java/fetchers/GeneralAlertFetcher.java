package fetchers;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import fetchers.response.sachet.alerts.Alert;
import fetchers.response.sachet.alerts.LocationAlertResponse;
import utils.HttpUtils;

import java.net.URI;
import java.util.Collections;
import java.util.List;

public class GeneralAlertFetcher {

    private static final String BASE_URL =
            "https://sachet.ndma.gov.in/cap_public_website/";
    private static final Gson gson = new Gson();

    /**
     * Fetches and parses CAP alerts for a specific point from NDMA SACHET.
     * Returns an empty list (never null) if the request fails or parsing fails,
     * so callers don't need null-checks.
     */
    public static List<Alert> fetchAlertForThisLocation(double latitude, double longitude, int radiusKm) {
        String endpoint = "FetchLocationWiseAlerts";
        String query = "lat=" + latitude + "&long=" + longitude + "&radius=" + radiusKm;
        URI uri = URI.create(BASE_URL +endpoint+ "?" + query);

        String rawResponse = HttpUtils.sendPostNoBody(uri.toString());

        try {
            LocationAlertResponse response = gson.fromJson(rawResponse, LocationAlertResponse.class);

            if (response == null || response.alerts() == null) {
                return Collections.emptyList();
            }
            return response.alerts();

        } catch (JsonSyntaxException e) {
            System.err.println("Failed to parse SACHET response: " + rawResponse);
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    public static void main(String[] args) {
        System.out.println(fetchAlertForThisLocation(25.7585, 84.1489,50));
//        System.out.println(fetchWeatherForThisLocation(25.7585, 84.1489));
    }

    //TODO: Add support for FetchAllAlerts

    public static String fetchWeatherForThisLocation(double latitude, double longitude) {
        String endpoint = "GetWeatherInfo";
        String url = new StringBuilder(BASE_URL+endpoint)
                .append("?lat=").append(latitude)
                .append("&lng=").append(longitude)   // note: lng, not long
                .toString();

        return HttpUtils.sendPostNoBody(url);
    }


}