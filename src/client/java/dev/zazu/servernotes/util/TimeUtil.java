package dev.zazu.servernotes.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeUtil {
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("d MMM uuuu HH:mm")
        .withZone(ZoneId.systemDefault());

    private TimeUtil() {
    }

    public static String now() {
        return Instant.now().toString();
    }

    public static String display(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isBlank()) {
            return "Unknown";
        }
        try {
            return DISPLAY.format(Instant.parse(isoTimestamp));
        } catch (RuntimeException ignored) {
            return isoTimestamp;
        }
    }
}
