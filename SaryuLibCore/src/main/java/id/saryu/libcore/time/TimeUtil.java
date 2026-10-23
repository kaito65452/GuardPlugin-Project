package id.saryu.libcore.time;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Time utilities: formatted timestamps with WIB / WITA / WIT zone labels.
 */
public class TimeUtil {

    private static final Map<String, String> ZONE_MAP = new HashMap<>();
    static {
        ZONE_MAP.put("WIB", "Asia/Jakarta");
        ZONE_MAP.put("WITA", "Asia/Makassar");
        ZONE_MAP.put("WIT", "Asia/Jayapura");
        ZONE_MAP.put("UTC", "UTC");
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm");

    /**
     * Format now (or given epoch millis) as dd/MM/yy HH:mm + zone label.
     */
    public @NotNull String format(long millis, @NotNull String zoneKey) {
        ZoneId zone = zoneOf(zoneKey);
        ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone);
        return zdt.format(FMT) + " " + zoneKey.toUpperCase();
    }

    public @NotNull String formatNow(@NotNull String zoneKey) {
        return format(System.currentTimeMillis(), zoneKey);
    }

    public @NotNull ZoneId zoneOf(@NotNull String zoneKey) {
        String id = ZONE_MAP.getOrDefault(zoneKey.toUpperCase(), "Asia/Jakarta");
        try {
            return ZoneId.of(id);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    public @NotNull String zoneLabel(@NotNull String zoneKey) {
        return zoneKey.toUpperCase();
    }
}
