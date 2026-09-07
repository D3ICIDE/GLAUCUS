package fetchers.response.incois.sva;

import java.util.List;
import java.util.Map;

public record DistrictAdvisory(
        int fidCoasta, String districtName, String state,
        String geometryGeoJson,
        Map<Integer, String> colorByWidth,             // width -> "orange"/"green"
        Map<String, Map<Integer, List<DayAdvisory>>> textByLangAndWidth  // "ENG" -> {4 -> [day1, day2, day3]}
) {}
