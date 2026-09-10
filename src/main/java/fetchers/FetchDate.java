//package fetchers;
//
//import dev.langchain4j.agent.tool.Tool;
//
//import java.time.LocalDateTime;
//import java.time.format.DateTimeFormatter;
//
//public class FetchDate {
//    @Tool("Provides the user with the current Date")
//    public  String getCurrentDate() {
//        // Formats the date like: "2026-06-18"
//        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
//        return LocalDateTime.now().format(dateFormatter);
//    }
//}
