package id.saryu.libcore.punishment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationParserTest {

    private final DurationParser parser = new DurationParser();

    @Test
    void parsesCombinedDurations() {
        assertEquals((24L * 60L * 60L + 12L * 60L * 60L + 30L * 60L) * 1000L,
                parser.parse("1d12h30m").millis);
        assertFalse(parser.parse("1d12h30m").permanent);
    }

    @Test
    void permanentIsExplicitOnly() {
        assertTrue(parser.parse("perm").permanent);
        assertEquals(-1L, parser.parse("PERM").millis);
    }

    @Test
    void invalidInputDoesNotCrash() {
        assertEquals(0L, parser.parse("not-a-duration").millis);
        assertFalse(parser.parse("").permanent);
    }
}
