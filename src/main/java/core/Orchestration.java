package core;

import agents.*;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.observability.AgentMonitor;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import tools.*;
import utils.Config;
import utils.SseSessionBroker;

import java.util.Map;

public class Orchestration {
    public static enum RequestCategory {
        DOMAIN, SMALL_TALK
    }
    private static AgentMonitor monitor = new AgentMonitor();

    private static final Orchestration INSTANCE = new Orchestration();
    public static Orchestration getInstance() { return INSTANCE; }

    private final GlaucusPipeline pipeline;
    private final SseSessionBroker broker = new SseSessionBroker();

    public SseSessionBroker getBroker() {
        return broker;
    }


    public Orchestration() {
        ChatMemoryProvider chatMemoryProvider = memoryId -> MessageWindowChatMemory.withMaxMessages(15);


        ChatModel Gmodel = GoogleAiGeminiChatModel.builder()
                .apiKey(System.getenv("GEMINI_API_KEY"))
                .modelName("gemini-3.1-flash-lite")
                .returnThinking(true)
                .sendThinking(true)
                .build();
        ChatModel analysisModel = OpenAiChatModel.builder()
                .modelName(Config.routingModel)
                .apiKey(Config.Groq_API_KEY)
                .baseUrl(Config.API_URL)
                .build();
        ChatModel model = OpenAiChatModel.builder()
                .modelName(Config.mainModel)
                .apiKey(Config.Groq_API_KEY)
                .baseUrl(Config.API_URL)
                .build();

        ChatModel misModel = MistralAiChatModel.builder()
                .apiKey(Config.mistralApi)
                .modelName("mistral-medium-latest")
                .responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build())
                .returnThinking(true)
                .sendThinking(true)
                .build();

        ChatModel Gmodel2 = GoogleAiGeminiChatModel.builder()
                .apiKey(System.getenv("GEMINI_API_KEY"))
                .modelName("gemini-3.5-flash-lite")
                .returnThinking(true)
                .sendThinking(true)
                .build();


        ChatModel Gmodel3 = GoogleAiGeminiChatModel.builder()
                .apiKey(System.getenv("GEMINI_API_KEY"))
                .modelName("gemma-4-31b-it")
                .returnThinking(true)
                .sendThinking(true)
                .build();

        GeoSpatialReasoningAgent miniMe = AgenticServices.agentBuilder(GeoSpatialReasoningAgent.class)
                .chatModel(Gmodel)
                .tools(new GeoFenceToolWrapper(), new LocationToolWrapper(), new PoiToolWrapper())
                .build();

        RiskAssessmentAgent insurance = AgenticServices.agentBuilder(RiskAssessmentAgent.class)
                .chatModel(Gmodel2)
                .tools(new GeoFenceToolWrapper(), new HazardAlertToolWrapper())
                .build();

        MarineDataDiscoveryAgent nerd = AgenticServices.agentBuilder(MarineDataDiscoveryAgent.class)
                .chatModel(Gmodel3)
                .tools(new OceanDataToolWrapper())
                .outputKey("marineData")
                .build();


        GlaucusSupervisor glaucus = AgenticServices.supervisorBuilder(GlaucusSupervisor.class)
                .chatModel(model)
                .chatMemoryProvider(chatMemoryProvider)
                .responseStrategy(SupervisorResponseStrategy.SUMMARY)
                .subAgents(miniMe, insurance, nerd)
                .listener(monitor)
                .outputKey("supervisorResult")
                .build();


        ReportingAgent jameson = AgenticServices.agentBuilder(ReportingAgent.class)
                .chatModel(Gmodel3)
                .chatMemoryProvider(chatMemoryProvider)
                .outputKey("explanation")
                .build();

        this.pipeline = AgenticServices.sequenceBuilder(GlaucusPipeline.class)
                .subAgents(glaucus, jameson)
                .listener(new StreamingAgentListener(broker))
                .outputKey("explanation")
                .build();
    }

    public String queryLlm(String query, String sessionId) {
        try {
            return pipeline.chat(sessionId, query);
        } finally {
            broker.publish(sessionId, Map.of("type", "done"));
            broker.close(sessionId);
        }
    }



}