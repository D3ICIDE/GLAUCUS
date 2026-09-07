package fetchers.response.imd.stations;

import java.util.Map;

public record StationObservation(
        String stationId,
        String stationType,
        String stationName,
        Map<String, String> fields // raw label -> value, since field sets differ per type
) {}
