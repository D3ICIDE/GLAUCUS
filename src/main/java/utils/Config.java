package utils;

import io.github.cdimascio.dotenv.Dotenv;

public class Config {
    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    public static final String SVAS_ADVISORY_URL =
            "https://incois.gov.in/oceanservices/SVAS/SVAS_Advisory.geojson";
    public static final String Landing_Center_Endpoint =
            "https://incois.gov.in/geoserver/PFZ_LandingCentres/ows?service=WFS&version=1.1.0&request=GetFeature&typeName=PFZ_LandingCentres:LandingCenters_29Apr2024&outputFormat=application/json";
    public static final String EEZ_Endpoint =
            "https://incois.gov.in/geoserver/PFZ_EEZ/wfs?service=WFS&version=1.1.0&request=GetFeature&typeName=PFZ_EEZ:indiaeez&outputFormat=application/json";
    public static final String PORT_WARNING_URL = "https://rsmcnewdelhi.imd.gov.in/port-warning.php";
    public static final String EEZ_POLYGON_Endpoint =
            "https://geo.vliz.be/geoserver/wfs?service=WFS&version=1.1.0&request=GetFeature&typeName=MarineRegions:eez&outputFormat=application/json&CQL_FILTER=mrgid%20IN%20(8480,8333)";






    //CHANGES
    static Dotenv dotenv = Dotenv.load();
    public static String mistralApi= dotenv.get("mistral_api");
    public static String tRouterApi= dotenv.get("token_router_api");
    private static volatile String currentUserMessage;
    public static final String mainModel = "openai/gpt-oss-120b";
    public static final String routingModel = "openai/gpt-oss-20b";

    public static final String analysisModel = "z-ai/glm-5.3-free";
    public static final String backgroundCheckingModel= "openai/gpt-oss-20b";
    public static final String visualModel ="";
    //public static final double temperature =0.5;
    public final static String Nvidia_API_KEY = System.getenv("NVIDIA_API_KEY");
    public static final String nVIDIAModel = "nvidia/nemotron-3-ultra-550b-a55b:free";
    public static final String exaUrl = "https://api.exa.ai/search";
    public static final String tokenRouterBaseURL = "https://api.tokenrouter.com/v1";
    public static final String ExaApiKey = System.getenv("exa_API");


    public final static String Groq_API_KEY = System.getenv("Groq_API_KEY");
    public final static String OpenRouters_API_KEY = System.getenv("Open_Router_API_Key");
    public final static String Weather_API_KEY = System.getenv("Weather_API_KEY");
    public final static String API_URL = "https://api.groq.com/openai/v1";
    public final static String OpenRouters = "https://openrouter.ai/api/v1";
    public final static String NVIDIA_URL = "https://integrate.api.nvidia.com/v1";
    public static final String TAVILY_API_URL = "https://api.tavily.com";
    public static final String Tavily_Extract_URL = "https://api.tavily.com/extract";
    public static final String Weather_Base_URL = "http://api.weatherapi.com/v1";
    public static final String GEO_API_URL = "http://ip-api.com/json/";
    public static final String TAVILY_API_KEY = System.getenv("tavily_API_KEY");
}
