package id.saryu.libcore.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringSanitizerTest {

    @Test
    void normalizesCaseSlashWhitespaceAndFullWidthCharacters() {
        assertEquals("minecraft:op", StringSanitizer.normalize("/／ＭＩＮＥＣＲＡＦＴ：ＯＰ"));
        assertEquals("gamemode creative", StringSanitizer.normalize("  /GAMEMODE   creative  "));
    }

    @Test
    void stripsZeroWidthBidiAndControlCharacters() {
        assertEquals("op", StringSanitizer.normalize("o\u200Bp\u202E\u0000"));
        assertEquals("execute run op", StringSanitizer.normalize("/execute\u200B run op"));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals("", StringSanitizer.normalize(null));
        assertEquals("", StringSanitizer.normalize("////"));
    }
}
