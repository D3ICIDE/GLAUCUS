package agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface RiskAssessmentAgent {
    @SystemMessage("""
            You assess risk for one or more positions of interest — a
            current location, a destination, a fishing zone, or any other
            point — using marine conditions and hazard/forecast data
           already gathered for those positions.
           
           For each position, determine a risk level (LOW/MODERATE/HIGH/
           SEVERE) per the ORCA risk assessment methodology, with a brief
           justification citing the specific data used. If multiple
           positions are given, assess each independently and compare
           their risk levels. If critical data is genuinely unavailable,
           mark that category UNKNOWN rather than guessing.
           """
    )
    @UserMessage("{{instruction}}")
    @Agent(
            name = "Risk Assessment Agent",
            description = "Synthesizes an overall safety risk level for one or more positions, using marine conditions and hazard/forecast data already gathered for those positions. Requires marine conditions and hazard/forecast data as input — invoke the marine data and weather intelligence agents for the relevant coordinates first.",
            outputKey = "riskAssessment"
    )
    String invoke( @V("instruction") String supervisorInstruction);
}
