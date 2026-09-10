package core;

import agents.*;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.observability.AgentMonitor;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.model.mistralai.MistralAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.TokenStream;
import tools.*;
import utils.Config;
import utils.SseSessionBroker;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

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


        ChatModel gModel = GoogleAiGeminiChatModel.builder()
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

        ChatModel sModel = OpenAiChatModel.builder()
                .modelName("thinkingmachines/inkling:free")
                .apiKey(Config.OpenRouters_API_KEY)
                .baseUrl(Config.OpenRouters)
                .build();
        ChatModel model = OpenAiChatModel.builder()
                .modelName(Config.analysisModel)
                .apiKey("sk-yephb1hfwP92EBcwreDSXwm3nEN1lGLgvFtVdWBqxtWyV8vQ")
                .baseUrl(Config.tokenRouterBaseURL)
                .build();

        ChatModel mModel = OpenAiChatModel.builder()
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


        StreamingChatModel misModelStreaming = MistralAiStreamingChatModel.builder()
                .apiKey(Config.mistralApi)
                .modelName("ministral-14b-2512")
                .returnThinking(true)
                .sendThinking(true)
                .build();

        ChatModel gModel2 = GoogleAiGeminiChatModel.builder()
                .apiKey(System.getenv("GEMINI_API_KEY"))
                .modelName("gemini-3.5-flash-lite")
                .returnThinking(true)
                .sendThinking(true)
                .build();


        ChatModel gModel3 = GoogleAiGeminiChatModel.builder()
                .apiKey(System.getenv("GEMINI_API_KEY"))
                .modelName("gemma-4-31b-it")
                .returnThinking(true)
                .sendThinking(true)
                .build();

        StreamingChatModel gModelStream = GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(System.getenv("GEMINI_API_KEY"))
                .modelName("gemini-3.5-flash-lite")
                .returnThinking(true)
                .sendThinking(true)
                .build();

        GeoSpatialReasoningAgent miniMe = AgenticServices.agentBuilder(GeoSpatialReasoningAgent.class)
                .chatModel(gModel)
                .tools(new GeoFenceToolWrapper(), new LocationToolWrapper(), new PoiToolWrapper())
                .build();

        RiskAssessmentAgent insurance = AgenticServices.agentBuilder(RiskAssessmentAgent.class)
                .chatModel(gModel2)
                .tools(new GeoFenceToolWrapper(), new HazardAlertToolWrapper(), new LocationToolWrapper())
                .build();

        MarineDataDiscoveryAgent nerd = AgenticServices.agentBuilder(MarineDataDiscoveryAgent.class)
                .chatModel(gModel2)
                .tools(new OceanDataToolWrapper(), new LocationToolWrapper())
                .outputKey("marineData")
                .build();


        ChatModel oRouterModel = OpenAiChatModel.builder()
                .apiKey(Config.OpenRouters_API_KEY)
                .modelName("minimax/minimax-m3:free")
                .baseUrl(Config.OpenRouters)
                //.strictJsonSchema(true)

                .build();
        GlaucusSupervisor glaucus = AgenticServices.supervisorBuilder(GlaucusSupervisor.class)
                .chatModel(mModel)
                .chatMemoryProvider(chatMemoryProvider)
                .responseStrategy(SupervisorResponseStrategy.SUMMARY)
                .subAgents(miniMe, insurance, nerd)
                .listener(monitor)
                .outputKey("supervisorResult")
                .build();


        ReportingAgent jameson = AgenticServices.agentBuilder(ReportingAgent.class)
                .streamingChatModel(gModelStream)
                .chatMemoryProvider(chatMemoryProvider)
                .outputKey("explanation")
                .build();

        this.pipeline = AgenticServices.sequenceBuilder(GlaucusPipeline.class)
                .subAgents(glaucus, jameson)
                .listener(new StreamingAgentListener(broker))
                .outputKey("explanation")
                .build();
    }

    public String queryLlm(String query, String sessionId){
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder fullText = new StringBuilder();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        try {
            TokenStream tokenStream = pipeline.chat(sessionId, query);

            tokenStream
                    .onPartialResponse(token -> {
                        fullText.append(token);
                        broker.publish(sessionId, Map.of("type", "token", "text", token));
                    })
                    .onCompleteResponse(response -> {
                        broker.publish(sessionId, Map.of("type", "done"));
                        broker.close(sessionId);
                        latch.countDown();
                    })
                    .onError(error -> {
                        broker.publish(sessionId, Map.of(
                                "type", "agent_error",
                                "agent", "GLAUCUS",
                                "message", String.valueOf(error.getMessage())
                        ));
                        broker.publish(sessionId, Map.of("type", "done"));
                        broker.close(sessionId);
                        errorRef.set(error);
                        latch.countDown();
                    })
                    .start();
        } catch (Exception e) {
            broker.publish(sessionId, Map.of(
                    "type", "agent_error",
                    "agent", "GLAUCUS",
                    "message", String.valueOf(e.getMessage())
            ));

            broker.publish(sessionId, Map.of("type", "done"));
            broker.close(sessionId);
            throw new RuntimeException(e);

        }

        try {
            latch.await(); // cheap to block here — this runs on a virtual thread
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for streamed response", e);
        }

        Throwable err = errorRef.get();
        if (err != null) {
            throw new RuntimeException(err);
        }
        return fullText.toString();
    }



}