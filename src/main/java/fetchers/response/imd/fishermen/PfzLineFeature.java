package fetchers.response.imd.fishermen;

public record PfzLineFeature(
        String featureId,
        String category,
        int julianDay,
        int year,
        String sno,
        String uid,
        double lengthKm,
        String geomJson
) {}
