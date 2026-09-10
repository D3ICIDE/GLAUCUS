package core;


import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import tools.GeoFenceToolWrapper;
import utils.MapContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class Server {
  private final Orchestration orchestration;
  private final Gson gson = new Gson();

    public Server(Orchestration orchestration) {
        this.orchestration = orchestration;
    }
    public void start(int port) throws Exception{
        HttpServer server = HttpServer.create(new InetSocketAddress(port),0);
        System.out.println("Server started running on http://localhost:"+port);

            server.createContext("/api/v1/events", this::handleEvents);
            server.createContext("/api/v1/geofence", this::handleGeofenceCheck);
            server.createContext("/api/v1/chat", this::handleChat);
            /*TODO
            server.createContext("api/v1/poi",handler)
            server.createContext("api/v1/routeOptimization",handler)

             */


        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }
    private void handleEvents(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String sessionId = exchange.getRequestURI().getQuery().split("=")[1];
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); // TODO: lock down for prod
        exchange.sendResponseHeaders(200, 0);
        orchestration.getBroker().register(sessionId, exchange.getResponseBody());
    }
    private void handleGeofenceCheck(com.sun.net.httpserver.HttpExchange exchange){
        var params = parseQuery(exchange.getRequestURI().getQuery());
        double lat = Double.parseDouble(params.get("lat"));
        double lon = Double.parseDouble(params.get("lon"));

        try {
            String json = GeoFenceToolWrapper.getHazardsWithGeometryForMap(lat, lon, 50000);


        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); // TODO: lock down for prod
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
        }catch (Exception e){
            System.out.println("GeoFenceToolWrapper.getHazardsWithGeometryForMap() failed");
        }
    }
    private void handleChat(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject req = JsonParser.parseString(body).getAsJsonObject();
            String message = req.get("message").getAsString();
            double lat = req.get("lat").getAsDouble();
            double lon = req.get("lon").getAsDouble();
            String sessionId = req.get("sessionId").getAsString();

            String promptWithLocation = String.format(
//                    "[User Co-ordinates] %.4f°N, %.4f°E] %s", lat, lon, message
                    message
            );
            System.out.println(message);
            String answerText = "";

           try{ answerText = orchestration.queryLlm(promptWithLocation, sessionId);}
           catch (RuntimeException e){
               System.out.println("Exception caught");
               System.out.println(e.getMessage());
           }

            JsonArray hazards = MapContext.drainHazardGeometry();
            List<MapContext.InterestPoint> interest = MapContext.drain(); // legacy POI/vessel marker path

            boolean anyHazardous = false;
            for (JsonElement el : hazards) {
                JsonObject hit = el.getAsJsonObject();
                String risk = hit.has("riskLevel") && !hit.get("riskLevel").isJsonNull()
                        ? hit.get("riskLevel").getAsString().toLowerCase() : "";
                if (!risk.isEmpty() && !risk.equals("safe")) {
                    anyHazardous = true;
                    break;
                }
            }

            JsonObject resp = new JsonObject();
            resp.addProperty("text", answerText);
            resp.addProperty("tag", anyHazardous ? "HAZARD ALERT" : null);
            resp.addProperty("warn", anyHazardous);
            resp.add("mapUpdate", hazards);

            writeJson(exchange, 200, resp);
        } catch (Exception e) {
            e.printStackTrace();
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            writeJson(exchange, 500, err);
        } finally {
            exchange.close();
        }
    }
    private void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, JsonObject payload) throws java.io.IOException {
        byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); // TODO: lock down for prod
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static Map<String, String> parseQuery(String query) {
        var map = new HashMap<String, String>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            map.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return map;
    }
}
