package id.saryu.antichatmanager.blacklist;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.libcore.util.StringSanitizer;
import id.saryu.libcore.util.TtlCache;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Core blacklist manager. Maintains an in-memory cache of blacklist entries and
 * provides command matching with namespace/alias resolution and nested-command
 * scanning.
 *
 * <p>Entries are always added to memory immediately so newly scanned/added
 * commands are visible right away; the database row id is reconciled
 * asynchronously afterwards.</p>
 */
public class BlacklistManager {

    public enum MatchMode { EXACT, STARTSWITH, REGEX, WILDCARD, ARGUMENT }

    public enum Source { MANUAL, PLUGIN_SCAN, LUCKPERMS_SCAN, IMPORT }

    public static class Entry {
        public int id;
        @NotNull public String command = "";
        @NotNull public String normalized = "";
        @NotNull public MatchMode mode = MatchMode.EXACT;
        @NotNull public Source source = Source.MANUAL;
        @Nullable public String pluginName;
        @Nullable public String permission;
        @Nullable public String reason;
        @Nullable public String addedBy;
        public long addedAt;
        @Nullable public String world;
        @Nullable public String groupName;
        public boolean enabled = true;
        public Pattern regexPattern;

        public Entry() {}

        public void compileRegex() {
            if (mode == MatchMode.REGEX) {
                try {
                    regexPattern = Pattern.compile(command, Pattern.CASE_INSENSITIVE);
                } catch (Exception e) {
                    regexPattern = null;
                }
            } else {
                regexPattern = null;
            }
        }
    }

    private final AntiChatManager plugin;
    private final Map<Integer, Entry> entriesById = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> entriesByCommand = new ConcurrentHashMap<>();
    private final TtlCache<String, String> matchCache = new TtlCache<>(5000, 20000);
    private final AtomicInteger tempIds = new AtomicInteger(-1);
    private final CommandMap commandMap;
    private final java.util.concurrent.CompletableFuture<Void> loadedFuture =
            new java.util.concurrent.CompletableFuture<>();

    public BlacklistManager(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
        this.commandMap = Bukkit.getServer().getCommandMap();
        loadEntries();
    }

    private void loadEntries() {
        plugin.getLib().getSchedulerModule().runAsync(() -> {
            try {
                List<Entry> loaded = plugin.getStorage().loadBlacklistEntries().join();
                int added = 0;
                for (Entry e : loaded) {
                    if (e.mode == null) e.mode = MatchMode.EXACT;
                    if (e.source == null) e.source = Source.MANUAL;
                    e.normalized = StringSanitizer.normalize(e.command);
                    e.compileRegex();
                    if (contains(e.normalized)) continue; // duplicate row from an earlier run
                    addEntryInternal(e);
                    added++;
                }
                plugin.getLogger().info("Loaded " + added + " blacklist entries from storage.");
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to load blacklist entries: " + ex.getMessage());
            } finally {
                loadedFuture.complete(null);
            }
        });
    }

    /** Completes once the initial database load has finished (success or failure). */
    public @NotNull java.util.concurrent.CompletableFuture<Void> whenLoaded() {
        return loadedFuture;
    }

    public boolean isLoaded() {
        return loadedFuture.isDone();
    }

    public synchronized void addEntryInternal(@NotNull Entry e) {
        entriesById.put(e.id, e);
        List<Entry> list = entriesByCommand.computeIfAbsent(e.normalized, k -> new ArrayList<>());
        // Avoid duplicate objects for the same command id.
        list.removeIf(x -> x.id == e.id);
        list.add(e);
        matchCache.clear();
    }

    /**
     * Add an entry to memory immediately, then persist it. Returns the same entry
     * (its id may change from a temporary negative value to the DB id).
     */
    public @NotNull Entry addEntry(@NotNull Entry e) {
        if (e.normalized == null || e.normalized.isEmpty()) {
            e.normalized = StringSanitizer.normalize(e.command);
        }
        e.compileRegex();
        if (e.addedAt == 0) e.addedAt = System.currentTimeMillis();
        if (e.addedBy == null) e.addedBy = "AntiChatManager";
        if (e.id == 0) e.id = tempIds.getAndDecrement();
        addEntryInternal(e);

        final int tempId = e.id;
        plugin.getStorage().saveBlacklistEntry(e).thenAccept(dbId -> {
            if (dbId > 0) rebindId(e, tempId, dbId);
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Could not persist blacklist entry '" + e.command
                    + "' (kept in memory): " + ex.getMessage());
            return null;
        });
        return e;
    }

    private synchronized void rebindId(@NotNull Entry e, int oldId, int newId) {
        if (entriesById.get(oldId) == e) {
            entriesById.remove(oldId);
            e.id = newId;
            entriesById.put(newId, e);
        }
    }

    /** Whether an equivalent entry (same normalized command + mode) already exists. */
    public boolean contains(@NotNull String command) {
        String norm = StringSanitizer.normalize(command);
        String only = commandOnly(norm);
        List<Entry> list = entriesByCommand.get(norm);
        if (list != null && !list.isEmpty()) return true;
        list = entriesByCommand.get(only);
        if (list != null && !list.isEmpty()) return true;
        for (Entry entry : entriesById.values()) {
            if (entry.mode != MatchMode.EXACT && matches(entry, norm, only)) {
                return true;
            }
        }
        return false;
    }

    public boolean removeEntry(int id) {
        Entry e = entriesById.remove(id);
        if (e == null) return false;
        List<Entry> list = entriesByCommand.get(e.normalized);
        if (list != null) {
            list.removeIf(x -> x.id == id);
            if (list.isEmpty()) entriesByCommand.remove(e.normalized);
        }
        matchCache.clear();
        plugin.getStorage().deleteBlacklistEntry(id);
        return true;
    }

    /** Remove all entries for a command (any source). Returns how many were removed. */
    public int removeByCommand(@NotNull String command, @Nullable Source onlySource) {
        String norm = StringSanitizer.normalize(command);
        String target = commandOnly(norm);
        List<Entry> matches = new ArrayList<>();
        for (Entry e : entriesById.values()) {
            if (onlySource != null && e.source != onlySource) continue;
            if (e.normalized.equals(norm)
                    || commandOnly(e.normalized).equals(target)) {
                matches.add(e);
            }
        }
        for (Entry e : matches) {
            removeEntry(e.id);
        }
        return matches.size();
    }

    public @Nullable Entry getEntry(int id) {
        return entriesById.get(id);
    }

    public @Nullable Entry findEntry(@NotNull String command) {
        String norm = StringSanitizer.normalize(command);
        String only = commandOnly(norm);
        List<Entry> list = entriesByCommand.get(norm);
        if (list != null && !list.isEmpty()) return list.get(0);
        list = entriesByCommand.get(only);
        if (list != null && !list.isEmpty()) return list.get(0);
        for (Entry e : entriesById.values()) {
            if (e.enabled && matches(e, norm, only)) return e;
        }
        return null;
    }

    /**
     * Match a raw command string. Returns the blacklist entry that matched, or
     * null. Handles namespaces, aliases and nested commands.
     */
    public @Nullable String match(@NotNull String rawCommand) {
        String norm = StringSanitizer.normalize(rawCommand);
        if (norm.isEmpty()) return null;

        String cached = matchCache.get(norm);
        if (cached != null) return cached.isEmpty() ? null : cached;

        String only = commandOnly(norm);

        Entry exact = findExact(norm);
        if (exact == null) exact = findExact(only);
        if (exact == null) exact = findExactByAlias(only);
        if (exact != null) {
            matchCache.put(norm, exact.command);
            return exact.command;
        }

        for (List<Entry> list : entriesByCommand.values()) {
            for (Entry e : list) {
                if (!e.enabled) continue;
                if (matches(e, norm, only)) {
                    matchCache.put(norm, e.command);
                    return e.command;
                }
            }
        }

        matchCache.put(norm, "");
        return null;
    }

    /**
     * Match the command and its nested commands (execute run, return run, ...).
     */
    public @Nullable String matchWithNested(@NotNull String rawCommand) {
        String direct = match(rawCommand);
        if (direct != null) return direct;
        return scanNested(rawCommand);
    }

    private @Nullable Entry findExact(String cmd) {
        List<Entry> list = entriesByCommand.get(cmd);
        if (list == null) return null;
        for (Entry e : list) {
            if (e.mode == MatchMode.EXACT && e.enabled) return e;
        }
        return null;
    }

    private @Nullable Entry findExactByAlias(@NotNull String base) {
        for (String alias : resolveAliases(base)) {
            Entry e = findExact(alias);
            if (e != null) return e;
        }
        return null;
    }

    private boolean matches(@NotNull Entry e, @NotNull String norm, @NotNull String only) {
        switch (e.mode) {
            case EXACT:
                // "op" must also block "/op Steve".
                return e.normalized.equals(norm)
                        || e.normalized.equals(only)
                        || norm.startsWith(e.normalized + " ");
            case STARTSWITH:
                return norm.startsWith(e.normalized) || only.startsWith(e.normalized);
            case WILDCARD:
                return wildcardMatch(e.normalized, norm) || wildcardMatch(e.normalized, only);
            case REGEX:
                if (e.regexPattern != null) {
                    return e.regexPattern.matcher(norm).matches() || e.regexPattern.matcher(only).matches();
                }
                return false;
            case ARGUMENT:
                return e.normalized.equals(norm);
            default:
                return false;
        }
    }

    /** First command token without namespace: "minecraft:op Steve" -> "op". */
    public @NotNull String commandOnly(@NotNull String command) {
        String norm = StringSanitizer.normalize(command);
        int space = norm.indexOf(' ');
        String first = space >= 0 ? norm.substring(0, space) : norm;
        return stripNamespace(first);
    }

    private boolean wildcardMatch(@NotNull String pattern, @NotNull String input) {
        String regex = Pattern.quote(pattern).replace("\\*", ".*").replace("\\?", ".");
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(input).matches();
    }

    /**
     * Strip namespace prefix: minecraft:op -> op, bukkit:pl -> pl.
     */
    public @NotNull String stripNamespace(@NotNull String cmd) {
        int colon = cmd.indexOf(':');
        if (colon > 0) {
            String before = cmd.substring(0, colon).toLowerCase(Locale.ROOT);
            if (before.equals("minecraft") || before.equals("bukkit") || before.equals("plugin")
                    || before.equals("cmd") || before.equals("command") || before.equals("paper")) {
                return cmd.substring(colon + 1);
            }
        }
        return cmd;
    }

    /**
     * Resolve aliases via CommandMap. Returns all known aliases for a command.
     */
    public @NotNull Set<String> resolveAliases(@NotNull String command) {
        Set<String> aliases = new HashSet<>();
        String base = stripNamespace(command).toLowerCase(Locale.ROOT);
        try {
            Command cmd = commandMap.getCommand(base);
            if (cmd != null) {
                aliases.add(cmd.getName().toLowerCase(Locale.ROOT));
                for (String alias : cmd.getAliases()) {
                    aliases.add(alias.toLowerCase(Locale.ROOT));
                }
            }
            // Reverse lookup: commands that list this name as an alias.
            for (Command candidate : commandMap.getKnownCommands().values()) {
                List<String> candidateAliases = candidate.getAliases();
                if (candidateAliases == null) continue;
                for (String alias : candidateAliases) {
                    if (alias.equalsIgnoreCase(base)) {
                        aliases.add(candidate.getName().toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return aliases;
    }

    /**
     * Check a command string for nested commands (execute run, return run,
     * function, schedule, tellraw run_command) and return the first matched entry.
     */
    public @Nullable String scanNested(@NotNull String fullCommand) {
        String norm = StringSanitizer.normalize(fullCommand);
        if (norm.isEmpty()) return null;

        String[] parts = norm.split("\\s+");
        if (parts.length == 0) return null;

        String baseFirst = stripNamespace(parts[0]);

        switch (baseFirst) {
            case "execute", "return", "schedule" -> {
                int runIdx = indexOf(parts, "run");
                if (runIdx >= 0 && runIdx + 1 < parts.length) {
                    String nested = reconstruct(parts, runIdx + 1);
                    String matched = matchWithNested(nested);
                    if (matched != null) return matched;
                } else if (baseFirst.equals("schedule") && parts.length > 1) {
                    String matched = matchWithNested(reconstruct(parts, 1));
                    if (matched != null) return matched;
                }
            }
            case "function" -> {
                if (parts.length > 1) {
                    String matched = match(parts[1]);
                    if (matched != null) return matched;
                }
            }
            case "tellraw", "title", "book", "give" -> {
                // Look for run_command payloads inside JSON components.
                int idx = norm.indexOf("run_command");
                if (idx >= 0) {
                    String tail = norm.substring(idx);
                    int quote = tail.indexOf('"');
                    int start = tail.indexOf(':', quote);
                    if (start >= 0) {
                        String nested = tail.substring(start + 1).replaceFirst("^\\s*\"", "");
                        int end = nested.indexOf('"');
                        if (end > 0) nested = nested.substring(0, end);
                        String matched = matchWithNested(nested);
                        if (matched != null) return matched;
                    }
                }
            }
            default -> {
            }
        }

        return null;
    }

    private int indexOf(String[] parts, String word) {
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(word)) return i;
        }
        return -1;
    }

    private String reconstruct(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    public @NotNull Collection<Entry> getAllEntries() {
        return entriesById.values();
    }

    public @NotNull List<Entry> getSortedEntries() {
        List<Entry> list = new ArrayList<>(entriesById.values());
        list.sort(Comparator.comparing((Entry e) -> e.command.toLowerCase(Locale.ROOT))
                .thenComparingInt(e -> e.id));
        return list;
    }

    public @NotNull List<Entry> getPage(int page, int pageSize, @Nullable Source source,
                                       @Nullable String pluginFilter) {
        List<Entry> all = new ArrayList<>();
        for (Entry e : entriesById.values()) {
            if (source != null && e.source != source) continue;
            if (pluginFilter != null && (e.pluginName == null
                    || !e.pluginName.equalsIgnoreCase(pluginFilter))) continue;
            all.add(e);
        }
        all.sort(Comparator.comparing((Entry e) -> e.command.toLowerCase(Locale.ROOT))
                .thenComparingInt(e -> e.id));
        int from = Math.max(0, (page - 1) * pageSize);
        if (from >= all.size()) return List.of();
        int to = Math.min(all.size(), from + pageSize);
        return all.subList(from, to);
    }

    public int count(@Nullable Source source) {
        if (source == null) return entriesById.size();
        int n = 0;
        for (Entry e : entriesById.values()) {
            if (e.source == source) n++;
        }
        return n;
    }

    public int countByPlugin(@NotNull String pluginName) {
        int n = 0;
        for (Entry e : entriesById.values()) {
            if (e.pluginName != null && e.pluginName.equalsIgnoreCase(pluginName)) n++;
        }
        return n;
    }

    public @NotNull Set<String> getPluginNames() {
        Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Entry e : entriesById.values()) {
            if (e.pluginName != null && !e.pluginName.isBlank()) names.add(e.pluginName);
        }
        return names;
    }

    public @NotNull Map<Source, Integer> countBySource() {
        Map<Source, Integer> counts = new java.util.EnumMap<>(Source.class);
        for (Source s : Source.values()) counts.put(s, 0);
        for (Entry e : entriesById.values()) counts.merge(e.source, 1, Integer::sum);
        return counts;
    }

    public void reload() {
        entriesById.clear();
        entriesByCommand.clear();
        matchCache.clear();
        loadEntries();
    }

    public @NotNull TtlCache<String, String> getMatchCache() {
        return matchCache;
    }
}
