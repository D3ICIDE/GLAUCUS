package core;

import agents.*;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.observability.AgentMonitor;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.TokenStream;
import tools.*;
import utils.Config;
import utils.SseSessionBroker;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class Orchestration {
    public enum RequestCategory {
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
        ChatMemoryProvider memory = memoryId -> MessageWindowChatMemory.withMaxMessages(15);

        // Models (one line each; provider details live in ModelFactory)
        ChatModel geoModel       = ModelFactory.gemini(Config.geoSpatialModel);
        ChatModel agentModel     = ModelFactory.gemini(Config.agentModel);
        ChatModel supervisorLlm  = ModelFactory.groq(Config.mainModel);

        // Tools: create once, share between agents (check the wrappers hold no per-agent state)
        var geoFence = new GeoFenceToolWrapper();
        var location = new LocationToolWrapper();
        var poi      = new PoiToolWrapper();
        var hazards  = new HazardAlertToolWrapper();
        var ocean    = new OceanDataToolWrapper();

        // Agents: only what actually differs is passed in (type, model, output key, tools)
        GeoSpatialReasoningAgent geoSpatial = agent(GeoSpatialReasoningAgent.class, geoModel, null,
                geoFence, location, poi);
        RiskAssessmentAgent risk = agent(RiskAssessmentAgent.class, agentModel, null,
                geoFence, hazards, location);
        MarineDataDiscoveryAgent marineData = agent(MarineDataDiscoveryAgent.class, agentModel, "marineData",
                ocean, location);

        GlaucusSupervisor supervisor = AgenticServices.supervisorBuilder(GlaucusSupervisor.class)
                .chatModel(supervisorLlm)
                .chatMemoryProvider(memory)
                .responseStrategy(SupervisorResponseStrategy.SUMMARY)
                .subAgents(geoSpatial, risk, marineData)
                .listener(monitor)
                .outputKey("supervisorResult")
                .build();

        ReportingAgent reporting = AgenticServices.agentBuilder(ReportingAgent.class)
                .streamingChatModel(ModelFactory.geminiStreaming(Config.reportingModel))
                .chatMemoryProvider(memory)
                .outputKey("explanation")
                .build();

        this.pipeline = AgenticServices.sequenceBuilder(GlaucusPipeline.class)
                .subAgents(supervisor, reporting)
                .listener(new StreamingAgentListener(broker))
                .outputKey("explanation")
                .build();
    }

    /** Builds a tool-using sub-agent. outputKey may be null. */
    private static <T> T agent(Class<T> type, ChatModel model, String outputKey, Object... tools) {
        var builder = AgenticServices.agentBuilder(type)
                .chatModel(model)
                .tools(tools);
        if (outputKey != null) {
            builder = builder.outputKey(outputKey);
        }
        return builder.build();
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