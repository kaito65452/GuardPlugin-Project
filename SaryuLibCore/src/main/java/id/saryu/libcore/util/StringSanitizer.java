package id.saryu.libcore.util;

/**
 * String sanitization used for command matching: strip unicode lookalikes,
 * zero-width characters, control characters, and normalize whitespace/case.
 */
public class StringSanitizer {

    private static final java.util.regex.Pattern ZERO_WIDTH = java.util.regex.Pattern.compile("[\\p{Cf}\\p{C}]");
    private static final java.util.regex.Pattern CONTROL = java.util.regex.Pattern.compile("[\\p{Cc}]");

    /**
     * Normalize raw player input for matching: trim, collapse whitespace,
     * strip leading '/', remove zero-width/control chars, lowercase.
     */
    public static String normalize(String input) {
        if (input == null) return "";
        String s = input;
        // Remove BOM and zero-width / control chars.
        s = ZERO_WIDTH.matcher(s).replaceAll("");
        s = CONTROL.matcher(s).replaceAll("");
        // Strip leading slashes.
        s = s.replaceFirst("^/+", "");
        // Collapse internal whitespace.
        s = s.trim().replaceAll("\\s+", " ");
        return s.toLowerCase();
    }

    public static String stripSlash(String input) {
        if (input == null) return "";
        return input.replaceFirst("^/+", "");
    }
}
