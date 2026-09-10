package tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import fetchers.GeneralAlertFetcher;
import fetchers.response.sachet.alerts.Alert;

import java.util.List;

public class HazardAlertToolWrapper {
    @Tool("This tool is used to fetch current Weather Hazards in the area")
    public static String fetchAlertForThisLocation(
            @P("latitude of the area") double lat,
            @P("longitude of the area") double lon,
            @P("Search radius in kilometers around the location, e.g.50km")int radiusKm){
        System.out.println("FetchAlertForThisLocation Called");
        List<Alert> alerts = GeneralAlertFetcher.fetchAlertForThisLocation(lat,lon,radiusKm);
        if (alerts == null || alerts.isEmpty()) {
            return "No active alerts found for this location.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(alerts.size()).append(" active alert(s):\n\n");

        for (int i = 0; i < alerts.size(); i++) {
            Alert a = alerts.get(i);
            sb.append(i + 1).append(". [").append(a.severity()).append("] ")
                    .append(a.disasterType()).append(" — ").append(a.areaDescription()).append("\n");
            sb.append("   Valid: ").append(a.effectiveStartTime())
                    .append(" to ").append(a.effectiveEndTime()).append("\n");
            sb.append("   Source: ").append(a.alertSource())
                    .append(" | Area covered: ~").append(a.areaCovered()).append(" km²\n");
            sb.append("   Message: ").append(a.warningMessage());
            if (!"en".equalsIgnoreCase(a.actualLang())) {
                sb.append(" (language: ").append(a.actualLang()).append(")");
            }
            sb.append("\n\n");
        }

        return sb.toString().trim();


    }

    public static void main(String[] args) {
        System.out.println(fetchAlertForThisLocation(25.785,84.1489,50));
    }
}
