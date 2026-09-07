package fetchers.response.sachet.alerts;


public record GeoJsonMultiPolygon(
        String type,
        double[][][][] coordinates
) {}