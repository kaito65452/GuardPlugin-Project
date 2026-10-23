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
        // NFKC folds full-width lookalikes and compatibility characters before
        // matching. It also turns full-width '/' into a normal slash.
        try {
            s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC);
        } catch (Exception ignored) {
        }
        // Remove BOM, zero-width, bidi controls and control chars.
        s = ZERO_WIDTH.matcher(s).replaceAll("");
        s = CONTROL.matcher(s).replaceAll("");
        // Strip leading spaces/slashes in any order (e.g. "  / / op").
        s = s.trim();
        while (s.startsWith("/")) {
            s = s.substring(1).trim();
        }
        // Collapse internal whitespace.
        s = s.replaceAll("\\s+", " ");
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    public static String stripSlash(String input) {
        if (input == null) return "";
        return input.replaceFirst("^/+", "");
    }
}
