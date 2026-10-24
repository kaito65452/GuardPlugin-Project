package id.saryu.libcore.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Shared interactive pagination component for Saryu plugins.
 *
 * <p>Renders navigable footers in the form:
 * <pre>[« Sebelumnya] Halaman 2/5 [Berikutnya »]</pre>
 * Supporting interactive clicks (run_command or suggest_command fallback),
 * localization (id/en), page number validation, and boundary conditions.</p>
 *
 * @param <T> Item type being paginated
 */
public class Paginator<T> {

    public enum Language {
        INDONESIAN("« Sebelumnya", "Berikutnya »", "Halaman", "Klik untuk ke halaman sebelumnya", "Klik untuk ke halaman berikutnya"),
        ENGLISH("« Previous", "Next »", "Page", "Click to go to previous page", "Click to go to next page");

        final String prevText;
        final String nextText;
        final String pageLabel;
        final String prevHover;
        final String nextHover;

        Language(String prevText, String nextText, String pageLabel, String prevHover, String nextHover) {
            this.prevText = prevText;
            this.nextText = nextText;
            this.pageLabel = pageLabel;
            this.prevHover = prevHover;
            this.nextHover = nextHover;
        }
    }

    private final List<T> items;
    private final int pageSize;
    private final int totalItems;
    private final int totalPages;

    public Paginator(@NotNull List<T> items, int pageSize) {
        Objects.requireNonNull(items, "items cannot be null");
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be > 0, got: " + pageSize);
        }
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.pageSize = pageSize;
        this.totalItems = this.items.size();
        this.totalPages = this.totalItems == 0 ? 1 : (int) Math.ceil((double) this.totalItems / pageSize);
    }

    public int getTotalItems() {
        return totalItems;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getTotalPages() {
        return totalPages;
    }

    /**
     * Check if the specified 1-based page number is strictly within valid bounds [1, totalPages].
     */
    public boolean isValidPage(int page) {
        return page >= 1 && page <= totalPages;
    }

    /**
     * Returns the 1-based clamped page within bounds [1, totalPages].
     */
    public int clampPage(int page) {
        if (page < 1) return 1;
        return Math.min(page, totalPages);
    }

    /**
     * Retrieve the items on the requested 1-based page.
     * If the page is out of range, an empty list is returned.
     */
    public @NotNull List<T> getPageItems(int page) {
        if (!isValidPage(page) || totalItems == 0) {
            return Collections.emptyList();
        }
        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(totalItems, fromIndex + pageSize);
        if (fromIndex >= totalItems) {
            return Collections.emptyList();
        }
        return items.subList(fromIndex, toIndex);
    }

    public boolean hasPrevious(int page) {
        return page > 1 && totalPages > 1;
    }

    public boolean hasNext(int page) {
        return page < totalPages && totalPages > 1;
    }

    /**
     * Build the pagination navigation footer Component:
     * <pre>[« Sebelumnya] Halaman 2/5 [Berikutnya »]</pre>
     *
     * @param page           Current 1-based page number
     * @param commandBuilder Function mapping target page number (1-based) to executable command string (e.g. "/sg words list page 2")
     * @param fallbackSuggest If true, use ClickEvent.Action.SUGGEST_COMMAND instead of RUN_COMMAND
     * @param lang           Language to use for navigation labels
     * @return Adventure Component representing the footer
     */
    public @NotNull Component buildFooter(
            int page,
            @NotNull Function<Integer, String> commandBuilder,
            boolean fallbackSuggest,
            @Nullable Language lang
    ) {
        Language l = lang != null ? lang : Language.INDONESIAN;
        int safePage = clampPage(page);

        TextComponent.Builder builder = Component.text();

        // 1. Previous button: [« Sebelumnya]
        if (hasPrevious(safePage)) {
            String cmd = commandBuilder.apply(safePage - 1);
            ClickEvent click = fallbackSuggest
                    ? ClickEvent.suggestCommand(cmd)
                    : ClickEvent.runCommand(cmd);

            Component prevBtn = Component.text("[" + l.prevText + "]")
                    .color(NamedTextColor.AQUA)
                    .clickEvent(click)
                    .hoverEvent(HoverEvent.showText(Component.text(l.prevHover).color(NamedTextColor.GRAY)));
            builder.append(prevBtn);
        } else {
            Component disabledPrev = Component.text("[" + l.prevText + "]")
                    .color(NamedTextColor.DARK_GRAY);
            builder.append(disabledPrev);
        }

        // 2. Middle page indicator: " Halaman X/Y "
        builder.append(Component.text(" " + l.pageLabel + " " + safePage + "/" + totalPages + " ")
                .color(NamedTextColor.GOLD));

        // 3. Next button: [Berikutnya »]
        if (hasNext(safePage)) {
            String cmd = commandBuilder.apply(safePage + 1);
            ClickEvent click = fallbackSuggest
                    ? ClickEvent.suggestCommand(cmd)
                    : ClickEvent.runCommand(cmd);

            Component nextBtn = Component.text("[" + l.nextText + "]")
                    .color(NamedTextColor.AQUA)
                    .clickEvent(click)
                    .hoverEvent(HoverEvent.showText(Component.text(l.nextHover).color(NamedTextColor.GRAY)));
            builder.append(nextBtn);
        } else {
            Component disabledNext = Component.text("[" + l.nextText + "]")
                    .color(NamedTextColor.DARK_GRAY);
            builder.append(disabledNext);
        }

        return builder.build();
    }

    /**
     * Convenience method using command template like "/sg words list page {n}".
     */
    public @NotNull Component buildFooter(
            int page,
            @NotNull String commandTemplate,
            boolean fallbackSuggest,
            @Nullable Language lang
    ) {
        Objects.requireNonNull(commandTemplate, "commandTemplate cannot be null");
        return buildFooter(
                page,
                targetPage -> commandTemplate.replace("{n}", String.valueOf(targetPage)),
                fallbackSuggest,
                lang
        );
    }
}
