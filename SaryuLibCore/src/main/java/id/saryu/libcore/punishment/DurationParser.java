package id.saryu.libcore.punishment;

import org.jetbrains.annotations.NotNull;

/**
 * Duration parser: supports s, m, h, d, w, mo, y, perm and combinations like
 * 1d12h30m. Returns millis and a permanent flag.
 */
public class DurationParser {

    private static final java.util.regex.Pattern PART = java.util.regex.Pattern.compile("(\\d+)\\s*(mo|[smhdwy])", java.util.regex.Pattern.CASE_INSENSITIVE);

    public @NotNull ParsedDuration parse(@NotNull String input) {
        if (input == null || input.isEmpty()) return new ParsedDuration(0, false);
        String s = input.trim();
        if (s.equalsIgnoreCase("perm")) return new ParsedDuration(-1, true);

        long millis = 0;
        java.util.regex.Matcher m = PART.matcher(s);
        boolean found = false;
        while (m.find()) {
            found = true;
            long value = Long.parseLong(m.group(1));
            String unit = m.group(2).toLowerCase();
            switch (unit) {
                case "s" -> millis += value * 1000L;
                case "m" -> millis += value * 60000L;
                case "h" -> millis += value * 3600000L;
                case "d" -> millis += value * 86400000L;
                case "w" -> millis += value * 604800000L;
                case "mo" -> millis += value * 2592000000L; // 30 days
                case "y" -> millis += value * 31536000000L; // 365 days
            }
        }
        if (!found) return new ParsedDuration(0, false);
        return new ParsedDuration(millis, false);
    }

    public static final class ParsedDuration {
        public final long millis;
        public final boolean permanent;
        public ParsedDuration(long millis, boolean permanent) {
            this.millis = millis;
            this.permanent = permanent;
        }
    }
}
