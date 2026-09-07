package tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import com.google.gson.JsonParser;
import utils.HttpUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class LocationToolWrapper {


//    @Tool("This tool is used to fetch the current location of the user.")
    public static String getMyAddress() {
        String[] coordinates = getWindowsPrecisionLocation();

        if (coordinates != null) {
            String lat = coordinates[0];
            String lon = coordinates[1];
            System.out.println("Local Coordinates Found: " + lat + ", " + lon);

            // Convert coordinates to a real address
            System.out.println("Resolving to physical address...");
//                String address = getAddressFromCoords(lat, lon);
            System.out.println("\n=== SUCCESS ===");
//                System.out.println("Address: " + address);
            System.out.println("===============");
            return lat + " " + lon;


        } else {
            System.out.println("Failed to determine PC location.");
            return "Failed to determine PC location.";
        }

    }


    private static String[] getWindowsPrecisionLocation() {
        String powershellLocationCmd = "src/main/resources/FetchMyLocation.ps1";
        try {
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", powershellLocationCmd);
            builder.redirectErrorStream(true);
            Process process = builder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.equals("DENIED")) {
                        System.err.println("Error: Location access is turned off in Windows Settings.");
                        return null;
                    } else if (line.startsWith("LOCATION:")) {
                        String[] coords = line.substring(9).split(",");
                        if (coords.length >= 2) {
                            return coords;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("PowerShell execution failed: " + e.getMessage());
        }
        return null;
    }

    //        @Tool("This tool is used to fetch the Physical Address from the Co-ordinates.")
//        private static String getAddressFromCoords(
//                @P("The latitude of the location") String lat,
//                @P("The longitude of the location ") String lon) {
//            String url = String.format("https://nominatim.openstreetmap.org/reverse?lat=%s&lon=%s&format=json", lat, lon);
//
//            try {
//                String locationResponse = HttpUtils.sendGET(url,"User-Agent","MyJavaLocationApp/1.0 (vaibhavr945@gmail.com),false");
//                // PARSE USING GSON
//                JsonObject response = gson.fromJson(locationResponse, JsonObject.class);
//                if (response.has("display_name")) {
//                    return response.get("display_name").getAsString();
//                }
//            } catch (Exception e) {
//                System.err.println("HTTP request failed: " + e.getMessage());
//            }
//            return "Address unavailable";
//        }
    @Tool("This tool is used to fetch the coordinates of any location. Use this tool whenever you need coordinate for any place apart from the current location.")
    public static String fetchCoordinates(
            @P("This is the location that you are trying to find. This take a free form query so be as specific as possibile.") String location) {
        System.out.println("Using GeoEncoding");
        try {
            String query = URLEncoder.encode(location + " India", StandardCharsets.UTF_8);
            String url = String.format("https://nominatim.openstreetmap.org/search?q=%s&format=json", query);

        String response = HttpUtils.sendGET(url, "User-Agent", "MyJavaLocationApp/1.0 (vaibhavr945@gmail.com)", false);
        JsonArray array = JsonParser.parseString(response).getAsJsonArray();
        if (!array.isEmpty()) {
            JsonObject firstResult = array.get(0).getAsJsonObject();
            String lat = firstResult.get("lat").getAsString();
            String lon = firstResult.get("lon").getAsString();
            String finalResponse = String.format("Latitude: %s \nLongitude: %s",lat,lon);
            System.out.println(url);
            System.out.println(url);
            return finalResponse;


        }
        }catch (Exception e){
            System.out.println("Failed to fetch coordinates: " + e.getMessage());

        }
        return "Failed to fetch Coordinates";



    }

    public static void main(String[] args) {
//    System.out.println(getMyAddress());
        System.out.println(fetchCoordinates("Kerala"));
    }
}



