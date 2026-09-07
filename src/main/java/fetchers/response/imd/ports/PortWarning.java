package fetchers.response.imd.ports;

    public record PortWarning(
            String name,
            double latitude,
            double longitude,
            String warningLevel,
            String message,
            boolean highlighted,
            String bulletinFile,
            String date
    ) {}

