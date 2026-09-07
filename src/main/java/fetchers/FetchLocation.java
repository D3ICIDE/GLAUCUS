package fetchers;


import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.langchain4j.agent.tool.Tool;
import utils.Config;
import utils.HttpUtils;

public class FetchLocation {
    public static Gson gson = new Gson();
    @Tool("Fetches the user's current local city and region via GeoIP lookup when no location is specified.")
    public static String getCurrentLocation(){
        String rawResponse =HttpUtils.sendGET(Config.GEO_API_URL,null,null,false);
        JsonObject jsonResponse = gson.fromJson(rawResponse, JsonObject.class);
        String city = jsonResponse.has("city") ? jsonResponse.get("city").getAsString() :"Delhi";
         String region = jsonResponse.has("region") ? jsonResponse.get("region").getAsString() :"";
        String country = jsonResponse.has("country") ? jsonResponse.get("country").getAsString() :"";
        return  city+" " +country;

    }

    public static void main(String[] args) {
        System.out.println(FetchLocation.getCurrentLocation());
    }

}
