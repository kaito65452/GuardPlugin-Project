package id.saryu.saryuguard.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextNormalizerTest {

    @Test
    @DisplayName("Homoglyph normalization: Cyrillic and Greek lookalikes mapped to Latin")
    void testHomoglyphs() {
        // Cyrillic "а" (\u0430) and "о" (\u043E)
        String input = "\u0430njing d\u043Enk";
        String normalized = TextNormalizer.normalize(input, true);
        assertEquals("anjing donk", normalized);

        // Cyrillic "с", "е"
        String input2 = "\u0441\u0435k";
        assertEquals("cek", TextNormalizer.normalize(input2, true));
    }

    @Test
    @DisplayName("Zero-width characters and RTL/bidi overrides are completely stripped")
    void testZeroWidthAndRtl() {
        // Zero-width space (\u200B) and RTL override (\u202E)
        String input = "a\u200Bnj\u202Ei\uFEFFng";
        String normalized = TextNormalizer.normalize(input, true);
        assertEquals("anjing", normalized);
    }

    @Test
    @DisplayName("Unicode NFKC normalization folds fullwidth and compatibility characters")
    void testNfkcNormalization() {
        // Fullwidth "ａｎｊｉｎｇ"
        String input = "\uFF41\uFF4E\uFF4A\uFF49\uFF4E\uFF47";
        String normalized = TextNormalizer.normalize(input, true);
        assertEquals("anjing", normalized);
    }

    @Test
    @DisplayName("Leet mapping transforms 0->o, 1->i, 3->e, 4->a, 5->s, 7->t, @->a, $->s, !->i")
    void testLeetMapping() {
        String input = "4nj1ng b@ng547";
        String normalized = TextNormalizer.normalize(input, true);
        assertEquals("anjing bangsat", normalized);
    }

    @Test
    @DisplayName("Collapsing repeated characters reduces letter runs (baaaanngggsaat -> bangsat)")
    void testCollapseRepeats() {
        String input = "baaaannngggssaaat";
        String normalized = TextNormalizer.normalize(input, true);
        assertEquals("bangsat", normalized);

        // Collapses consecutive duplicate letters
        assertEquals("helo", TextNormalizer.collapseRepeatedChars("hello"));
        assertEquals("god", TextNormalizer.collapseRepeatedChars("goooood"));
    }
}
