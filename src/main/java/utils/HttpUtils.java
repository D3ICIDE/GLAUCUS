package utils;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static utils.Config.DEFAULT_USER_AGENT;

public class HttpUtils {
        private static final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)) // ADDED
                .build();
    public static String sendPostNoBody(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Origin", "https://sachet.ndma.gov.in")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build();

            return executeRequest(request);
        } catch (Exception e) {
            e.printStackTrace();
            return "{\"error\": \"Network failure\"}";
        }
    }
    public static String sendPost(String url, String authHeader, String jsonPayload) {
        try{
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));
            if (authHeader != null) {
                builder.header("Authorization", authHeader);
            }

            return executeRequest(builder.build());
        }catch (Exception e){
            e.printStackTrace();
            return "{\"error\": \"Network failure\"}";
        }
    }
    public static String sendGET(String url,String headerName,String headerValue,Boolean statusCheck){
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .timeout(Duration.ofSeconds(10))
                    .GET();

            if (headerName != null && !headerName.isBlank() && headerValue != null) {
                builder.header(headerName, headerValue);
            }
            return executeRequest(builder.build(),statusCheck);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    private static String executeRequest(HttpRequest request,Boolean StatusCheck) throws java.io.IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
//        System.out.println(">>> HTTP REQUEST SENT " + response.statusCode() + " | BODY: " + response.body());
        if (response.statusCode() == 200) return response.body();
        else if (StatusCheck && response.statusCode()==500)return response.body();
        return "{\"error\": \"Status " + response.statusCode() + "\"}";
    }
    //OVERLOADING
    private static String executeRequest(HttpRequest request) throws java.io.IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
//        System.out.println(">>> HTTP REQUEST SENT " + response.statusCode() + " | BODY: " + response.body());
        if (response.statusCode() == 200) return response.body();
        return "{\"error\": \"Status " + response.statusCode() + "\"}";
    }
}
