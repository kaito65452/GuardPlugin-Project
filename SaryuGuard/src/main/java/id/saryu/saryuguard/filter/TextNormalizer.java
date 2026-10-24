package id.saryu.saryuguard.filter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Text normalizer pipeline for chat filtering.
 *
 * <p>Pipeline stages:
 * 1. Lowercase (Locale.ROOT)
 * 2. Unicode NFKC normalization
 * 3. Strip diacritics / combining marks
 * 4. Strip zero-width characters and RTL/bidi overrides
 * 5. Basic Cyrillic/Greek homoglyph mapping to Latin
 * 6. Leet speak substitution (0->o, 1->i, 3->e, 4->a, 5->s, 7->t, @->a, etc.)
 * 7. Collapse repeated letter runs (e.g. "baaaannnggg" -> "bang")</p>
 */
public class TextNormalizer {

    private static final Pattern ZERO_WIDTH_AND_BIDI = Pattern.compile(
            "[\u200B-\u200D\uFEFF\u2060\u202A-\u202E\u2066-\u2069\u00AD\u180E]"
    );

    private static final Pattern COMBINING_DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    // Homoglyphs map: Cyrillic & Greek lookalikes -> Latin equivalents
    private static final Map<Character, Character> HOMOGLYPHS = new HashMap<>();

    static {
        // Cyrillic lookalikes
        HOMOGLYPHS.put('\u0430', 'a'); // Cyrillic small a
        HOMOGLYPHS.put('\u0410', 'a'); // Cyrillic capital A
        HOMOGLYPHS.put('\u0441', 'c'); // Cyrillic small s
        HOMOGLYPHS.put('\u0421', 'c'); // Cyrillic capital S
        HOMOGLYPHS.put('\u0435', 'e'); // Cyrillic small e
        HOMOGLYPHS.put('\u0415', 'e'); // Cyrillic capital E
        HOMOGLYPHS.put('\u043E', 'o'); // Cyrillic small o
        HOMOGLYPHS.put('\u041E', 'o'); // Cyrillic capital O
        HOMOGLYPHS.put('\u0440', 'p'); // Cyrillic small r -> looks like p
        HOMOGLYPHS.put('\u0420', 'p'); // Cyrillic capital R -> looks like P
        HOMOGLYPHS.put('\u0445', 'x'); // Cyrillic small h -> looks like x
        HOMOGLYPHS.put('\u0425', 'x'); // Cyrillic capital H -> looks like X
        HOMOGLYPHS.put('\u0443', 'y'); // Cyrillic small u -> looks like y
        HOMOGLYPHS.put('\u0423', 'y'); // Cyrillic capital U -> looks like Y
        HOMOGLYPHS.put('\u0456', 'i'); // Cyrillic small i
        HOMOGLYPHS.put('\u0406', 'i'); // Cyrillic capital I
        HOMOGLYPHS.put('\u0458', 'j'); // Cyrillic small je -> looks like j
        HOMOGLYPHS.put('\u0408', 'j'); // Cyrillic capital Je -> looks like J
        HOMOGLYPHS.put('\u0455', 's'); // Cyrillic small dze -> looks like s

        // Greek lookalikes
        HOMOGLYPHS.put('\u03B1', 'a'); // Greek alpha
        HOMOGLYPHS.put('\u03BF', 'o'); // Greek omicron
        HOMOGLYPHS.put('\u03BD', 'v'); // Greek nu -> looks like v
        HOMOGLYPHS.put('\u03C1', 'p'); // Greek rho -> looks like p
        HOMOGLYPHS.put('\u03C4', 't'); // Greek tau
        HOMOGLYPHS.put('\u03BA', 'k'); // Greek kappa
    }

    /**
     * Complete normalization pipeline.
     */
    public static @NotNull String normalize(@Nullable String input, boolean collapseRepeats) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        // 1. Lowercase
        String s = input.toLowerCase(java.util.Locale.ROOT);

        // 2. Unicode NFKC normalization
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);

        // 3. Strip diacritics / combining marks
        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        s = COMBINING_DIACRITICS.matcher(s).replaceAll("");

        // 4. Strip zero-width characters and RTL overrides
        s = ZERO_WIDTH_AND_BIDI.matcher(s).replaceAll("");

        // 5. Homoglyphs replacement
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character mapped = HOMOGLYPHS.get(c);
            sb.append(mapped != null ? mapped : c);
        }
        s = sb.toString();

        // 6. Leet map: 0->o, 1->i, 3->e, 4->a, 5->s, 7->t, @->a, $->s, !->i
        s = applyLeetMap(s);

        // 7. Collapse repeated letter runs (e.g. "baaaangggsaaat" -> "bangsat")
        if (collapseRepeats) {
            s = collapseRepeatedChars(s);
        }

        return s;
    }

    public static @NotNull String applyLeetMap(@NotNull String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            char mapped = switch (c) {
                case '0' -> 'o';
                case '1', '!' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                default -> c;
            };
            sb.append(mapped);
        }
        return sb.toString();
    }

    public static @NotNull String collapseRepeatedChars(@NotNull String input) {
        if (input.length() <= 1) return input;
        StringBuilder sb = new StringBuilder(input.length());
        char prev = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c != prev) {
                sb.append(c);
                prev = c;
            }
        }
        return sb.toString();
    }
}
