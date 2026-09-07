package fetchers.response.sachet.alerts;

import com.google.gson.annotations.SerializedName;

public record Alert(
        String severity,
        long identifier,

        @SerializedName("effective_start_time")
        String effectiveStartTime,

        @SerializedName("effective_end_time")
        String effectiveEndTime,

        @SerializedName("area_json")
        String areaJson,

        @SerializedName("disaster_type")
        String disasterType,

        @SerializedName("area_description")
        String areaDescription,

        @SerializedName("severity_level")
        String severityLevel,

        int type,

        @SerializedName("actual_lang")
        String actualLang,

        @SerializedName("warning_message")
        String warningMessage,

        String disseminated,

        @SerializedName("severity_color")
        String severityColor,

        @SerializedName("alert_id_sdma_autoinc")
        long alertIdSdmaAutoinc,

        String centroid,

        @SerializedName("alert_source")
        String alertSource,

        @SerializedName("area_covered")
        String areaCovered,

        @SerializedName("sender_org_id")
        String senderOrgId
) {
    public boolean isDisseminated() {
        return "true".equalsIgnoreCase(disseminated);
    }

    public double areaCoveredSqKm() {
        try {
            return Double.parseDouble(areaCovered);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Returns [longitude, latitude] parsed from the "lon,lat" centroid string. */
    public double[] centroidLonLat() {
        try {
            String[] parts = centroid.split(",");
            return new double[]{ Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) };
        } catch (Exception e) {
            return null;
        }
    }
}