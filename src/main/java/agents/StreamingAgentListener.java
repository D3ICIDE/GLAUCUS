package agents;

import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import utils.SseSessionBroker;

import java.time.Instant;
import java.util.Map;

public class StreamingAgentListener implements AgentListener {

    private final SseSessionBroker broker;

    public StreamingAgentListener(SseSessionBroker broker) {
        this.broker = broker;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest request) {
        Object sessionId = request.agenticScope().memoryId();
        broker.publish(sessionId, Map.of(
                "type", "agent_start",
                "agent", request.agent().name(),          // real agent name, not method name
                "outputKey", String.valueOf(request.agent().outputKey()),
                "timestamp", Instant.now().toString()
        ));
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        Object sessionId = response.agenticScope().memoryId();
        broker.publish(sessionId, Map.of(
                "type", "agent_end",
                "agent", response.agent().name(),
                "outputKey", String.valueOf(response.agent().outputKey()),
                "output", String.valueOf(response.output())
        ));
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        // sessionId retrieval depends on what AgentInvocationError exposes —
        // if it has agenticScope(), same pattern as above
        broker.publish("unknown", Map.of(
                "type", "agent_error",
                "message", String.valueOf(error)
        ));
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }
}