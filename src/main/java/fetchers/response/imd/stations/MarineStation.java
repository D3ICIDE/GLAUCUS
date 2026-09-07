package fetchers.response.imd.stations;

public record MarineStation(
        double lat,
        double lon,
        String stationId,
        String stationType)
{}