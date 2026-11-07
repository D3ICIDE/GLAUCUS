package utils;

import io.github.cdimascio.dotenv.Dotenv;

public class Config {
    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    //URL for Data Sources
    public static final String SVAS_ADVISORY_URL =
            "https://incois.gov.in/oceanservices/SVAS/SVAS_Advisory.geojson";
    public static final String Landing_Center_Endpoint =
            "https://incois.gov.in/geoserver/PFZ_LandingCentres/ows?service=WFS&version=1.1.0&request=GetFeature&typeName=PFZ_LandingCentres:LandingCenters_29Apr2024&outputFormat=application/json";
    public static final String EEZ_Endpoint =
            "https://incois.gov.in/geoserver/PFZ_EEZ/wfs?service=WFS&version=1.1.0&request=GetFeature&typeName=PFZ_EEZ:indiaeez&outputFormat=application/json";
    public static final String PORT_WARNING_URL = "https://rsmcnewdelhi.imd.gov.in/port-warning.php";
    public static final String EEZ_POLYGON_Endpoint =
            "https://geo.vliz.be/geoserver/wfs?service=WFS&version=1.1.0&request=GetFeature&typeName=MarineRegions:eez&outputFormat=application/json&CQL_FILTER=mrgid%20IN%20(8480,8333)";
    


    //API KEYS
    static Dotenv dotenv = Dotenv.load();
    public static String mistralApi= dotenv.get("mistral_api");
    public static String tokenRouterApi= dotenv.get("token_router_api");
    public final static String nvidiaApi = dotenv.get("nvidia_api");
    public static final String exaApi = dotenv.get("exa_api");
    public final static String groqApi = dotenv.get("groq_api");
    public final static String openRouterApi = dotenv.get("open_router_api");
    public final static String weatherApi = dotenv.get("weather_api");
    public static final String tavilyApi = dotenv.get("tavily_api");
    public static final String geminiApi = dotenv.get("gemini_api");
    public static final String azureSpeechApi = dotenv.get("azure_speech_key");
    public static final String azureSpeechRegion = dotenv.get("azure_speech_region");

    public static final String DB_Password = dotenv.get("DB_Password");

    //LLM Models
    private static volatile String currentUserMessage;
    public static final String mainModel = "openai/gpt-oss-120b";
    public static final String routingModel = "openai/gpt-oss-20b";
    public static final String nVIDIAModel = "nvidia/nemotron-3-ultra-550b-a55b:free";
    public static final String backgroundCheckingModel= "openai/gpt-oss-20b";
    public static final String analysisModel = "z-ai/glm-5.3-free";
    public static final String visualModel ="";
    public static final String geoSpatialModel = "gemini-3.1-flash-lite";   // GeoSpatialReasoningAgent
    public static final String agentModel = "gemini-3.5-flash-lite";        // RiskAssessmentAgent, MarineDataDiscoveryAgent
    public static final String reportingModel = "gemini-3.5-flash-lite";    // ReportingAgent (streaming)
    public static final String gemmaModel = "gemma-4-31b-it";               // currently unused
    public static final String openRouterModel = "minimax/minimax-m3:free"; // currently unused
    public static final String inklingModel = "thinkingmachines/inkling:free"; // commented-out OpenRouter model
    public static final String mistralMainModel = "mistral-medium-latest";      // commented-out Mistral chat model
    public static final String mistralStreamingModel = "ministral-14b-2512";
    //public static final double temperature =0.5;

    //URL for Services
    public final static String groq_url = "https://api.groq.com/openai/v1";
    public final static String openrouter_url = "https://openrouter.ai/api/v1";
    public final static String nvidia_url = "https://integrate.api.nvidia.com/v1";
    public static final String tavily_url = "https://api.tavily.com";
    public static final String tavily_extract_url = "https://api.tavily.com/extract";
    public static final String weather_url = "http://api.weatherapi.com/v1";
    public static final String GEO_API_URL = "http://ip-api.com/json/";

}
