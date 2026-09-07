//package agents;
//
//import core.Orchestration;
//import dev.langchain4j.agentic.Agent;
//import dev.langchain4j.service.UserMessage;
//import dev.langchain4j.service.V;
//
//public interface RequestRouter {
//    @UserMessage("""
//            Categorize the following user request as 'DOMAIN' if it relates to
//            sea safety, tides, weather, hazards, or fishing, or 'SMALL_TALK' if it's
//            a greeting, thanks, or general chit-chat unrelated to marine conditions.
//            Reply with only one of those words.
//            The user request is: '{{request}}'.
//            """)
//    @Agent("Categorizes a request for Glaucus routing")
//    Orchestration.RequestCategory classify(@V("request") String request);
//}