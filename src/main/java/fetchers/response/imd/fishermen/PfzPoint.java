package fetchers.response.imd.fishermen;

public record PfzPoint(
        String locationName,
        String direction,
        int bearing,
        double distMinKm,
        double distMaxKm,
        double depthMinM,
        double depthMaxM,
        double latitude,
        double longitude,
        String secId
) {}
