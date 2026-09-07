package fetchers.response.imd.fishermen;

import java.time.LocalDateTime;

public record FishermenWarning(
        String region,
        String authority,
        String detailMessage,
        String message,
        LocalDateTime warningTime,
        String pdfUrl
) {}