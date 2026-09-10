package agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.V;

public interface GlaucusPipeline {
    @Agent(
            name = "Architect"
    )
    TokenStream chat(@MemoryId String memoryId,
                     @V("request") String query);
}
