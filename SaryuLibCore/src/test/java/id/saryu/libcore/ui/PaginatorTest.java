package id.saryu.libcore.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaginatorTest {

    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    @Test
    @DisplayName("Test 0 items: totalPages=1, empty list, both buttons disabled")
    void testZeroItems() {
        Paginator<String> paginator = new Paginator<>(Collections.emptyList(), 5);

        assertEquals(0, paginator.getTotalItems());
        assertEquals(1, paginator.getTotalPages());
        assertTrue(paginator.isValidPage(1));
        assertFalse(paginator.isValidPage(0));
        assertFalse(paginator.isValidPage(2));

        assertTrue(paginator.getPageItems(1).isEmpty());
        assertFalse(paginator.hasPrevious(1));
        assertFalse(paginator.hasNext(1));

        Component footer = paginator.buildFooter(1, "/test page {n}", false, Paginator.Language.INDONESIAN);
        String text = plain.serialize(footer);
        assertEquals("[« Sebelumnya] Halaman 1/1 [Berikutnya »]", text);
    }

    @Test
    @DisplayName("Test 1 item: totalPages=1, 1 item on page 1, buttons disabled")
    void testOneItem() {
        Paginator<String> paginator = new Paginator<>(List.of("only-one"), 5);

        assertEquals(1, paginator.getTotalItems());
        assertEquals(1, paginator.getTotalPages());
        assertTrue(paginator.isValidPage(1));
        assertFalse(paginator.isValidPage(0));
        assertFalse(paginator.isValidPage(2));

        List<String> page1 = paginator.getPageItems(1);
        assertEquals(1, page1.size());
        assertEquals("only-one", page1.get(0));

        assertFalse(paginator.hasPrevious(1));
        assertFalse(paginator.hasNext(1));

        Component footer = paginator.buildFooter(1, "/test page {n}", false, Paginator.Language.INDONESIAN);
        String text = plain.serialize(footer);
        assertEquals("[« Sebelumnya] Halaman 1/1 [Berikutnya »]", text);
    }

    @Test
    @DisplayName("Test exactly 1 page (5 items with pageSize 5): totalPages=1")
    void testExactOnePage() {
        List<String> items = List.of("a", "b", "c", "d", "e");
        Paginator<String> paginator = new Paginator<>(items, 5);

        assertEquals(5, paginator.getTotalItems());
        assertEquals(1, paginator.getTotalPages());
        assertEquals(5, paginator.getPageItems(1).size());
        assertFalse(paginator.hasPrevious(1));
        assertFalse(paginator.hasNext(1));

        Component footer = paginator.buildFooter(1, "/test page {n}", false, Paginator.Language.INDONESIAN);
        assertEquals("[« Sebelumnya] Halaman 1/1 [Berikutnya »]", plain.serialize(footer));
    }

    @Test
    @DisplayName("Test page out of range: negative, zero, beyond total pages")
    void testPageOutOfRange() {
        List<String> items = List.of("1", "2", "3", "4", "5", "6", "7");
        Paginator<String> paginator = new Paginator<>(items, 3); // 3 pages: 3, 3, 1

        assertEquals(3, paginator.getTotalPages());

        // Underflow: -1 and 0
        assertFalse(paginator.isValidPage(-1));
        assertFalse(paginator.isValidPage(0));
        assertEquals(1, paginator.clampPage(-1));
        assertEquals(1, paginator.clampPage(0));
        assertTrue(paginator.getPageItems(-1).isEmpty());
        assertTrue(paginator.getPageItems(0).isEmpty());

        // Overflow: 4 and 100
        assertFalse(paginator.isValidPage(4));
        assertFalse(paginator.isValidPage(100));
        assertEquals(3, paginator.clampPage(4));
        assertEquals(3, paginator.clampPage(100));
        assertTrue(paginator.getPageItems(4).isEmpty());
        assertTrue(paginator.getPageItems(100).isEmpty());

        // Clamping produces valid footer for out-of-range requested page
        Component footerUnder = paginator.buildFooter(0, "/test page {n}", false, Paginator.Language.INDONESIAN);
        assertEquals("[« Sebelumnya] Halaman 1/3 [Berikutnya »]", plain.serialize(footerUnder));

        Component footerOver = paginator.buildFooter(99, "/test page {n}", false, Paginator.Language.INDONESIAN);
        assertEquals("[« Sebelumnya] Halaman 3/3 [Berikutnya »]", plain.serialize(footerOver));
    }

    @Test
    @DisplayName("Test multi-page navigation and click events on page 2 of 5")
    void testMultiPageNavigation() {
        List<String> items = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            items.add("item-" + i);
        }
        Paginator<String> paginator = new Paginator<>(items, 5); // 5 pages

        assertEquals(5, paginator.getTotalPages());

        // Check page 2
        assertTrue(paginator.hasPrevious(2));
        assertTrue(paginator.hasNext(2));
        assertEquals(List.of("item-6", "item-7", "item-8", "item-9", "item-10"), paginator.getPageItems(2));

        Component footer = paginator.buildFooter(2, "/sg words list page {n}", false, Paginator.Language.INDONESIAN);
        assertEquals("[« Sebelumnya] Halaman 2/5 [Berikutnya »]", plain.serialize(footer));

        // Check click event on previous button (target page 1)
        TextComponent tc = (TextComponent) footer;
        Component prevComponent = tc.children().get(0);
        ClickEvent prevClick = prevComponent.clickEvent();
        assertNotNull(prevClick);
        assertEquals(ClickEvent.Action.RUN_COMMAND, prevClick.action());
        assertEquals("/sg words list page 1", prevClick.value());

        // Check click event on next button (target page 3)
        Component nextComponent = tc.children().get(2);
        ClickEvent nextClick = nextComponent.clickEvent();
        assertNotNull(nextClick);
        assertEquals(ClickEvent.Action.RUN_COMMAND, nextClick.action());
        assertEquals("/sg words list page 3", nextClick.value());
    }

    @Test
    @DisplayName("Test fallback suggest_command click event")
    void testFallbackSuggestCommand() {
        List<String> items = List.of("a", "b", "c", "d", "e", "f");
        Paginator<String> paginator = new Paginator<>(items, 2); // 3 pages

        Component footer = paginator.buildFooter(2, "/sg plugins page {n}", true, Paginator.Language.INDONESIAN);
        TextComponent tc = (TextComponent) footer;

        ClickEvent prevClick = tc.children().get(0).clickEvent();
        assertNotNull(prevClick);
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, prevClick.action());
        assertEquals("/sg plugins page 1", prevClick.value());

        ClickEvent nextClick = tc.children().get(2).clickEvent();
        assertNotNull(nextClick);
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, nextClick.action());
        assertEquals("/sg plugins page 3", nextClick.value());
    }

    @Test
    @DisplayName("Test English localization labels")
    void testEnglishLocalization() {
        List<String> items = List.of("a", "b", "c", "d");
        Paginator<String> paginator = new Paginator<>(items, 2);

        Component footer = paginator.buildFooter(1, "/cmd page {n}", false, Paginator.Language.ENGLISH);
        assertEquals("[« Previous] Page 1/2 [Next »]", plain.serialize(footer));
    }
}
