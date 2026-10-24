package id.saryu.antichatmanager.policy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Immutable snapshot of active command blacklist rules.
 * Replaced atomically via AtomicReference so command processing never touches the database.
 *
 * <p>Requirements:
 * Entri tanpa namespace cocok dengan nama polos dan semua varian ber-namespace.
 * Entri ber-namespace (essentials:op) hanya cocok persis.
 * Pencocokan string literal, bukan regex.</p>
 */
public final class BlacklistSnapshot {

    private final Set<String> rawCommands;
    private final Set<String> unnamespaced;
    private final Set<String> namespaced;
    private final Map<String, String> sourceLabels;

    public BlacklistSnapshot(@NotNull Collection<Entry> entries) {
        Set<String> raw = new LinkedHashSet<>();
        Set<String> unname = new HashSet<>();
        Set<String> name = new HashSet<>();
        Map<String, String> sources = new HashMap<>();

        for (Entry e : entries) {
            String norm = normalize(e.command);
            if (norm.isEmpty()) continue;

            raw.add(e.command);
            sources.put(norm, e.source != null ? e.source : "MANUAL");

            if (norm.contains(":")) {
                name.add(norm);
            } else {
                unname.add(norm);
            }
        }

        this.rawCommands = Collections.unmodifiableSet(raw);
        this.unnamespaced = Collections.unmodifiableSet(unname);
        this.namespaced = Collections.unmodifiableSet(name);
        this.sourceLabels = Collections.unmodifiableMap(sources);
    }

    public static class Entry {
        public final String command;
        public final String source;
        public final String addedBy;
        public final long addedAt;

        public Entry(@NotNull String command, @Nullable String source, @Nullable String addedBy, long addedAt) {
            this.command = command;
            this.source = source != null ? source : "MANUAL";
            this.addedBy = addedBy != null ? addedBy : "SYSTEM";
            this.addedAt = addedAt;
        }
    }

    public static @NotNull BlacklistSnapshot empty() {
        return new BlacklistSnapshot(Collections.emptyList());
    }

    public static @NotNull BlacklistSnapshot ofEmergency(@NotNull List<String> emergencyList) {
        List<Entry> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String cmd : emergencyList) {
            list.add(new Entry(cmd, "EMERGENCY", "CONFIG_FALLBACK", now));
        }
        return new BlacklistSnapshot(list);
    }

    /**
     * Check if a player's command token matches the blacklist.
     *
     * @param cmdToken First token of player input (without leading slash)
     * @return true if command is blacklisted
     */
    public boolean matches(@NotNull String cmdToken) {
        String norm = normalize(cmdToken);
        if (norm.isEmpty()) return false;

        if (norm.contains(":")) {
            // Player typed namespaced command (e.g. "minecraft:op" or "essentials:op")
            // 1. Strict match for namespaced blacklist entries
            if (namespaced.contains(norm)) {
                return true;
            }
            // 2. Unnamespaced blacklist entries match all namespaced variants
            int colonIndex = norm.indexOf(':');
            String base = norm.substring(colonIndex + 1);
            return unnamespaced.contains(base);
        } else {
            // Player typed plain command without namespace (e.g. "op")
            // Unnamespaced entries match plain name
            return unnamespaced.contains(norm);
            // Notice: namespaced blacklist entries (essentials:op) only match strictly,
            // so they do not match plain "op".
        }
    }

    /**
     * Check if resolved command aliases match the blacklist.
     */
    public boolean matchesAlias(@NotNull String canonicalName, @Nullable List<String> aliases) {
        if (matches(canonicalName)) {
            return true;
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (matches(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Set<String> getRawCommands() {
        return rawCommands;
    }

    public int size() {
        return rawCommands.size();
    }

    public boolean isEmpty() {
        return rawCommands.isEmpty();
    }

    public @Nullable String getSource(@NotNull String command) {
        return sourceLabels.get(normalize(command));
    }

    public static @NotNull String normalize(@Nullable String input) {
        if (input == null) return "";
        String s = input.trim().toLowerCase(Locale.ROOT);
        while (s.startsWith("/")) {
            s = s.substring(1).trim();
        }
        // Collapse internal whitespace
        s = s.replaceAll("\\s+", " ");
        return s;
    }
}
