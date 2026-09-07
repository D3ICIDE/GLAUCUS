package fetchers.response.incois.sva;

public  record DayAdvisory(
        int day,
        String date,
        String status,
        String text)
{}
