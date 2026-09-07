package fetchers.response.sachet.alerts;

import java.util.List;

public record LocationAlertResponse(
        List<Alert> alerts,
        String responseMessage
) {}