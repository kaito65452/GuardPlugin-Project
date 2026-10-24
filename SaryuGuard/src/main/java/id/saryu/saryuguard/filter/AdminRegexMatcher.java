package id.saryu.saryuguard.filter;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Admin regex matching engine using RE2J.
 *
 * <p>Requirements:
 * Regex admin HANYA lewat RE2J. Divalidasi dan dikompilasi saat config dimuat.
 * Tolak pola yang tidak didukung, terlalu panjang, atau melebihi batas jumlah.
 * Penggantian ruleset atomik setelah seluruh config valid; jika gagal, ruleset lama dipertahankan.</p>
 */
public class AdminRegexMatcher {

    private final Logger logger;
    private final AtomicReference<List<Pattern>> ruleset = new AtomicReference<>(Collections.emptyList());

    public AdminRegexMatcher(@Nullable Logger logger) {
        this.logger = logger != null ? logger : Logger.getLogger("SaryuGuard-RE2J");
    }

    /**
     * Atomically compiles and applies admin regex patterns.
     *
     * @param patterns  Raw regex pattern strings
     * @param maxCount  Maximum allowed pattern count (e.g. 50)
     * @param maxLength Maximum allowed length per regex string (e.g. 128)
     * @return true if compilation succeeded and ruleset was swapped; false if failed (old ruleset retained)
     */
    public boolean compileAndSet(@NotNull List<String> patterns, int maxCount, int maxLength) {
        if (patterns.size() > maxCount) {
            logger.warning("[CHAT FILTER] Jumlah regex admin (" + patterns.size() + ") melebihi batas maksimum ("
                    + maxCount + ")! Mempertahankan ruleset sebelumnya.");
            return false;
        }

        List<Pattern> compiledList = new ArrayList<>(patterns.size());

        for (int i = 0; i < patterns.size(); i++) {
            String raw = patterns.get(i);
            if (raw == null || raw.isBlank()) continue;

            String trimmed = raw.trim();
            if (trimmed.length() > maxLength) {
                logger.warning("[CHAT FILTER] Regex #" + (i + 1) + " melebihi batas panjang maksimum ("
                        + trimmed.length() + " > " + maxLength + "): '" + trimmed + "'! Ruleset dibatalkan.");
                return false;
            }

            try {
                // RE2J linear-time compilation
                Pattern compiled = Pattern.compile(trimmed);
                compiledList.add(compiled);
            } catch (PatternSyntaxException e) {
                logger.warning("[CHAT FILTER] Regex #" + (i + 1) + " tidak didukung atau memiliki sintaks salah: '"
                        + trimmed + "' (" + e.getMessage() + ")! Ruleset lama dipertahankan.");
                return false;
            }
        }

        // Atomic swap
        ruleset.set(Collections.unmodifiableList(compiledList));
        logger.info("[CHAT FILTER] " + compiledList.size() + " pola regex admin RE2J berhasil dikompilasi dan diterapkan.");
        return true;
    }

    public boolean matches(@NotNull String text) {
        List<Pattern> patterns = ruleset.get();
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    public int getRuleCount() {
        return ruleset.get().size();
    }
}
