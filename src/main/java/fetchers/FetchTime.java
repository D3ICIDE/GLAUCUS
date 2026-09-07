//package scouts;
//
//import dev.langchain4j.agent.tool.Tool;
//
//import java.time.LocalDateTime;
//import java.time.format.DateTimeFormatter;
//
//public class FetchTime {
//
//    @Tool("Provides the user with the correct time")
//    public  String getCurrentTime() {
//        // Formats the time like: "10:14 PM"
//        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("hh:mm a");
//        return LocalDateTime.now().format(timeFormatter);
//    }
//
//}
