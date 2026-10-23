package id.saryu.antichatmanager.blacklist;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.libcore.util.StringSanitizer;
import id.saryu.libcore.util.TtlCache;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.configuration.ConfigurationSection;
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

    public enum MatchMode { EXACT, STARTSWITH, REGEX, WILDCARD, ARGUMENT, SUBCOMMAND }

    public enum Source { MANUAL, PLUGIN_SCAN, LUCKPERMS_SCAN, IMPORT, CONFIG, MANUAL_SCAN }

    /** What should happen when an entry matches. */
    public enum Action {
        BLOCK, ALERT_ONLY, ALLOW;

        public static @NotNull Action parse(@Nullable String value) {
            if (value == null) return BLOCK;
            return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
                case "allow", "allowed", "permit" -> ALLOW;
                case "alert-only", "alert", "notify" -> ALERT_ONLY;
                default -> BLOCK;
            };
        }
    }

    /**
     * Result of a command match. The entry carries the configured action, so the
     * packet/event layers can distinguish block, alert-only and allow rules.
     */
    public static final class MatchResult {
        public final @Nullable Entry entry;
        public final @NotNull String command;
        public final @NotNull String rawCommand;

        public MatchResult(@Nullable Entry entry, @NotNull String command, @NotNull String rawCommand) {
            this.entry = entry;
            this.command = command;
            this.rawCommand = rawCommand;
        }

        public @NotNull Action action() {
            return entry == null || entry.action == null ? Action.BLOCK : entry.action;
        }

        public boolean isBlock() { return action() == Action.BLOCK; }
        public boolean isAlertOnly() { return action() == Action.ALERT_ONLY; }
        public boolean isAllow() { return action() == Action.ALLOW; }
    }

    private static final MatchResult NO_MATCH = new MatchResult(null, "", "");

    public static class Entry {
        public int id;
        @NotNull public String command = "";
        @NotNull public String normalized = "";
        @NotNull public MatchMode mode = MatchMode.EXACT;
        @NotNull public Source source = Source.MANUAL;
        @NotNull public Action action = Action.BLOCK;
        @Nullable public String pluginName;
        @Nullable public String permission;
        @Nullable public String reason;
        @Nullable public String addedBy;
        public long addedAt;
        @Nullable public String world;
        @Nullable public String groupName;
        /** LuckPerms groups allowed to run this command without a full bypass. */
        @NotNull public java.util.List<String> allowedGroups = new java.util.ArrayList<>();
        public boolean enabled = true;
        public Pattern regexPattern;

        public Entry() {}

        /** Source label persisted/shown for manual scans, e.g. manual-scan:WorldEdit. */
        public @NotNull String sourceLabel() {
            if (source == Source.MANUAL_SCAN && pluginName != null && !pluginName.isBlank()) {
                return "manual-scan:" + pluginName;
            }
            return source.name();
        }

        public void compileRegex() {
            if (mode == MatchMode.REGEX) {
                try {
                    regexPattern = isSafeRegex(command)
                            ? Pattern.compile(command, Pattern.CASE_INSENSITIVE) : null;
                } catch (Exception e) {
                    regexPattern = null;
                }
            } else {
                regexPattern = null;
            }
        }

        /**
         * Rejects common catastrophic-backtracking shapes and unsafe constructs.
         * This is intentionally conservative: blacklist regexes are short and
         * should never need nested quantifiers or backreferences.
         */
        public static boolean isSafeRegex(@Nullable String regex) {
            if (regex == null || regex.isBlank() || regex.length() > 128) return false;
            if (regex.matches(".*\\\\([1-9][0-9]*).*")) return false;
            if (regex.matches(".*\\([^)]*[+*][^)]*\\)[+*].*")) return false;
            if (regex.matches(".*\\([^)]*\\|[^)]*\\)[+*].*")) return false;
            if (regex.contains(".*.*") || regex.contains(".+.+")) return false;
            if (regex.contains("(?{")) return false;
            return true;
        }
    }

    private final AntiChatManager plugin;
    private final Map<Integer, Entry> entriesById = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> entriesByCommand = new ConcurrentHashMap<>();
    private final TtlCache<String, String> matchCache = new TtlCache<>(5000, 20000);
    private final TtlCache<String, MatchResult> resultCache = new TtlCache<>(5000, 20000);
    private final AtomicInteger tempIds = new AtomicInteger(-1);
    private final CommandMap commandMap;
    private final java.util.concurrent.CompletableFuture<Void> loadedFuture =
            new java.util.concurrent.CompletableFuture<>();

    public BlacklistManager(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
        this.commandMap = Bukkit.getServer().getCommandMap();
        // Config rules are loaded first so they take precedence over stale
        // database rows with the same command (explicit admin intent wins).
        loadConfigRules();
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
                    if (e.action == null) e.action = Action.BLOCK;
                    e.normalized = canonicalize(e.command);
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

    /**
     * Load the admin-managed {@code commands:} section. These rules use literal
     * token matching (never regex) and support block, alert-only and allow.
     */
    public void loadConfigRules() {
        ConfigurationSection section = plugin.getConfigModule().get().getConfigurationSection("commands");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            if (key == null || key.isBlank()) continue;
            String normalized = canonicalize(key);
            if (normalized.isBlank() || normalized.length() > 256) {
                plugin.getLogger().warning("Ignoring invalid commands rule (empty or too long): " + key);
                continue;
            }
            ConfigurationSection rule = section.getConfigurationSection(key);
            String mode = rule != null ? rule.getString("mode", "block") : section.getString(key, "block");
            if (mode == null || !(mode.equalsIgnoreCase("block") || mode.equalsIgnoreCase("alert-only")
                    || mode.equalsIgnoreCase("alert") || mode.equalsIgnoreCase("allow")
                    || mode.equalsIgnoreCase("allowed"))) {
                plugin.getLogger().warning("Invalid mode in commands rule '" + key + "'; using block.");
            }
            Entry e = new Entry();
            e.id = tempIds.getAndDecrement();
            e.command = key.trim();
            e.normalized = normalized;
            e.mode = MatchMode.SUBCOMMAND;
            e.source = Source.CONFIG;
            e.action = Action.parse(mode);
            e.reason = "config commands rule";
            e.addedBy = "config";
            e.addedAt = System.currentTimeMillis();
            e.compileRegex();
            addEntryInternal(e);
        }
    }

    /** Re-read only the config rules, keeping database entries untouched. */
    public synchronized void reloadConfigRules() {
        List<Entry> remove = new ArrayList<>();
        for (Entry e : entriesById.values()) {
            if (e.source == Source.CONFIG) remove.add(e);
        }
        for (Entry e : remove) removeInMemory(e.id);
        loadConfigRules();
        matchCache.clear();
        resultCache.clear();
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
        resultCache.clear();
    }

    /**
     * Add an entry to memory immediately, then persist it. Returns the same entry
     * (its id may change from a temporary negative value to the DB id).
     */
    public @NotNull Entry addEntry(@NotNull Entry e) {
        if (e.normalized == null || e.normalized.isEmpty()) {
            e.normalized = canonicalize(e.command);
        }
        if (e.action == null) e.action = Action.BLOCK;
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
        String norm = canonicalize(command);
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
        Entry e = entriesById.get(id);
        if (e == null) return false;
        removeInMemory(id);
        if (e.source == Source.CONFIG) {
            removeConfigRule(e.command);
        } else if (id > 0) {
            plugin.getStorage().deleteBlacklistEntry(id);
        }
        return true;
    }

    private void removeInMemory(int id) {
        Entry e = entriesById.remove(id);
        if (e == null) return;
        List<Entry> list = entriesByCommand.get(e.normalized);
        if (list != null) {
            list.removeIf(x -> x.id == id);
            if (list.isEmpty()) entriesByCommand.remove(e.normalized);
        }
        matchCache.clear();
        resultCache.clear();
    }

    /** Remove all entries for a command (any source). Returns how many were removed. */
    public int removeByCommand(@NotNull String command, @Nullable Source onlySource) {
        String norm = canonicalize(command);
        String target = commandOnly(norm);
        List<Entry> matches = new ArrayList<>();
        for (Entry e : entriesById.values()) {
            if (onlySource != null && e.source != onlySource) continue;
            if (e.normalized.equals(norm)
                    || (!norm.contains(" ") && commandOnly(e.normalized).equals(target))) {
                matches.add(e);
            }
        }
        for (Entry e : matches) {
            removeEntry(e.id);
        }
        return matches.size();
    }

    public boolean removeConfigRulePublic(@NotNull String command) {
        String norm = canonicalize(command);
        ConfigurationSection section = plugin.getConfigModule().get().getConfigurationSection("commands");
        if (section == null) return false;
        boolean removed = false;
        for (String key : new ArrayList<>(section.getKeys(false))) {
            if (canonicalize(key).equals(norm)) {
                section.set(key, null);
                removed = true;
            }
        }
        if (removed) {
            plugin.getConfigModule().save();
            reloadConfigRules();
        }
        return removed;
    }

    private void removeConfigRule(@NotNull String command) {
        ConfigurationSection section = plugin.getConfigModule().get().getConfigurationSection("commands");
        if (section == null) return;
        for (String key : new ArrayList<>(section.getKeys(false))) {
            if (canonicalize(key).equals(canonicalize(command))) {
                section.set(key, null);
            }
        }
        plugin.getConfigModule().save();
    }

    /** True when an exact (not prefix) rule with this normalized command exists. */
    public boolean hasExactEntry(@NotNull String command) {
        return findExactEntry(command) != null;
    }

    public @Nullable Entry findExactEntry(@NotNull String command) {
        String norm = canonicalize(command);
        for (Entry e : entriesById.values()) {
            if (e.normalized.equals(norm)) return e;
        }
        return null;
    }

    /**
     * Insert or update a manual-scan entry. Manual/allow rules are never
     * overwritten: the caller checks {@link #findExactEntry(String)} first.
     */
    public @NotNull Entry addScannedEntry(@NotNull Entry e) {
        e.normalized = canonicalize(e.command);
        if (e.action == null) e.action = Action.BLOCK;
        if (e.id == 0) e.id = tempIds.getAndDecrement();
        e.compileRegex();
        addEntryInternal(e);
        final int tempId = e.id;
        plugin.getStorage().upsertManualScanEntry(e).thenAccept(dbId -> {
            if (dbId > 0) rebindId(e, tempId, dbId);
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Could not persist manual-scan entry '" + e.command
                    + "': " + ex.getMessage());
            return null;
        });
        return e;
    }

    /** Disable a scan-owned entry in memory and storage (row is retained). */
    public boolean disableEntry(int id) {
        Entry e = entriesById.get(id);
        if (e == null) return false;
        removeInMemory(id);
        if (id > 0) plugin.getStorage().setBlacklistEntryEnabled(id, false);
        return true;
    }

    /** Disable all scan-owned rows for one plugin (disabled/uninstalled). */
    public int disableManualScanEntriesForPlugin(@NotNull String pluginName) {
        int disabled = 0;
        for (Entry e : new ArrayList<>(entriesById.values())) {
            if (e.source != Source.MANUAL_SCAN) continue;
            if (e.pluginName == null || !e.pluginName.equalsIgnoreCase(pluginName)) continue;
            if (disableEntry(e.id)) disabled++;
        }
        return disabled;
    }

    public @Nullable Entry getEntry(int id) {
        return entriesById.get(id);
    }

    /**
     * Allow one LuckPerms group to execute a blacklisted command without giving
     * that group the global bypass permission. Additive: the entry stays active
     * for every other group.
     */
    public boolean addAllowedGroup(@NotNull String command, @NotNull String group) {
        Entry entry = findEntry(command);
        if (entry == null || group.isBlank()) return false;
        String normalized = group.trim().toLowerCase(Locale.ROOT);
        if (!entry.allowedGroups.contains(normalized)) {
            entry.allowedGroups.add(normalized);
            plugin.getStorage().updateAllowedGroups(entry.id, entry.allowedGroups);
        }
        return true;
    }

    public @Nullable Entry findEntry(@NotNull String command) {
        MatchResult result = resolve(command);
        return result == null ? null : result.entry;
    }

    /**
     * Match a raw command string. Returns the matched entry's command, or null.
     * Kept for API/backwards compatibility; new code should use
     * {@link #resolve(String)} so the per-entry action is available.
     */
    public @Nullable String match(@NotNull String rawCommand) {
        MatchResult result = resolve(rawCommand);
        return result == null ? null : result.command;
    }

    /**
     * Resolve a command using longest-prefix token matching. Aliases and
     * namespaces are canonicalized before comparison; config rules use literal
     * tokens (with {@code *} matching exactly one argument), never regex.
     */
    public @Nullable MatchResult resolve(@NotNull String rawCommand) {
        String norm = canonicalize(rawCommand);
        if (norm.isEmpty() || norm.length() > 256) return null;

        MatchResult cached = resultCache.get(norm);
        if (cached != null) return cached.entry == null ? null : cached;

        Set<String> variants = commandVariants(norm);
        Entry best = null;
        int bestScore = Integer.MIN_VALUE;

        for (Entry e : entriesById.values()) {
            if (!e.enabled) continue;
            for (String variant : variants) {
                String only = commandOnly(variant);
                if (!matches(e, variant, only)) continue;
                int score = specificity(e);
                if (score > bestScore) {
                    best = e;
                    bestScore = score;
                }
            }
        }

        if (best == null) {
            resultCache.put(norm, NO_MATCH);
            matchCache.put(norm, "");
            return null;
        }
        MatchResult result = new MatchResult(best, best.command, rawCommand);
        resultCache.put(norm, result);
        matchCache.put(norm, best.command);
        return result;
    }

    /**
     * Match the command and its nested commands (execute run, return run, ...).
     */
    public @Nullable String matchWithNested(@NotNull String rawCommand) {
        MatchResult result = resolveWithNested(rawCommand);
        return result == null ? null : result.command;
    }

    /** Action-aware nested resolver used by packet/event interception. */
    public @Nullable MatchResult resolveWithNested(@NotNull String rawCommand) {
        return resolveWithNested(rawCommand, 0);
    }

    private @Nullable MatchResult resolveWithNested(@NotNull String rawCommand, int depth) {
        if (depth > 8) return null;
        MatchResult direct = resolve(rawCommand);
        if (direct != null && !direct.isAllow()) return direct;
        MatchResult nested = scanNestedResult(rawCommand, depth);
        if (nested != null) return nested;
        return direct;
    }

    /**
     * Canonical command form: normalize, strip a namespace from the first
     * token, and retain the rest ("worldguard:wg reload" -> "wg reload").
     */
    public @NotNull String canonicalize(@NotNull String command) {
        String norm = StringSanitizer.normalize(command);
        if (norm.isEmpty()) return "";
        int space = norm.indexOf(' ');
        String first = space >= 0 ? norm.substring(0, space) : norm;
        int colon = first.indexOf(':');
        if (colon > 0) {
            first = first.substring(colon + 1);
        }
        return space < 0 ? first : first + norm.substring(space);
    }

    private @NotNull Set<String> commandVariants(@NotNull String canonical) {
        Set<String> variants = new java.util.LinkedHashSet<>();
        variants.add(canonical);
        int space = canonical.indexOf(' ');
        String first = space >= 0 ? canonical.substring(0, space) : canonical;
        String rest = space >= 0 ? canonical.substring(space) : "";
        for (String alias : resolveAliases(first)) {
            if (alias == null || alias.isBlank() || alias.indexOf(' ') >= 0) continue;
            variants.add(alias.toLowerCase(Locale.ROOT) + rest);
        }
        return variants;
    }

    private int specificity(@NotNull Entry e) {
        String[] tokens = e.normalized.split(" ");
        int literals = 0;
        for (String token : tokens) if (!"*".equals(token)) literals++;
        int source = switch (e.source) {
            case CONFIG -> 50;
            case MANUAL -> 40;
            case IMPORT -> 30;
            case PLUGIN_SCAN -> 20;
            case LUCKPERMS_SCAN -> 10;
            case MANUAL_SCAN -> 25;
        };
        int mode = switch (e.mode) {
            case EXACT, SUBCOMMAND, ARGUMENT -> 20;
            case STARTSWITH -> 10;
            case WILDCARD, REGEX -> 5;
        };
        int action = switch (e.action == null ? Action.BLOCK : e.action) {
            case ALLOW -> 3;
            case ALERT_ONLY -> 2;
            case BLOCK -> 1;
        };
        return tokens.length * 1000 + literals * 100 + mode * 10 + source + action;
    }

    private boolean matches(@NotNull Entry e, @NotNull String norm, @NotNull String only) {
        switch (e.mode) {
            case EXACT:
                // "op" must also block "/op Steve".
                return e.normalized.equals(norm)
                        || e.normalized.equals(only)
                        || norm.startsWith(e.normalized + " ");
            case SUBCOMMAND:
                return tokenMatch(e.normalized, norm);
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

    /**
     * Literal longest-prefix token comparison. {@code *} consumes exactly one
     * input token; no regex is compiled from configuration. Input length is
     * already bounded by {@link #resolve(String)}.
     */
    private boolean tokenMatch(@NotNull String pattern, @NotNull String input) {
        String[] pat = pattern.split(" ");
        String[] in = input.split(" ");
        if (in.length < pat.length) return false;
        for (int i = 0; i < pat.length; i++) {
            if (!"*".equals(pat[i]) && !pat[i].equals(in[i])) return false;
        }
        return true;
    }

    /** First command token without namespace: "minecraft:op Steve" -> "op". */
    public @NotNull String commandOnly(@NotNull String command) {
        String norm = StringSanitizer.normalize(command);
        int space = norm.indexOf(' ');
        String first = space >= 0 ? norm.substring(0, space) : norm;
        return stripNamespace(first);
    }

    public boolean isSafeRegex(@Nullable String regex) {
        return Entry.isSafeRegex(regex);
    }

    /**
     * Per-player exceptions: world restriction, single-group restriction, and
     * conflict-detection allowed-groups. Returning true means "this player is
     * allowed to run the matched entry" (so the caller should not cancel).
     */
    public boolean isAllowedFor(@NotNull org.bukkit.entity.Player player, @NotNull String matchedCommand) {
        Entry entry = findEntry(matchedCommand);
        if (entry == null) return false;
        if (entry.world != null && !entry.world.isBlank()
                && !entry.world.equalsIgnoreCase(player.getWorld().getName())) {
            return true;
        }
        if (entry.groupName != null && !entry.groupName.isBlank()
                && !player.hasPermission("group." + entry.groupName)) {
            return true;
        }
        for (String group : entry.allowedGroups) {
            if (group != null && !group.isBlank() && player.hasPermission("group." + group)) {
                return true;
            }
        }
        try {
            if (plugin.getConflictDetector() != null
                    && plugin.getConflictDetector().isAllowedForPlayer(entry.command, player)) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
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
        MatchResult result = scanNestedResult(fullCommand, 0);
        return result == null ? null : result.command;
    }

    private @Nullable MatchResult scanNestedResult(@NotNull String fullCommand, int depth) {
        if (depth > 8) return null;
        String norm = canonicalize(fullCommand);
        if (norm.isEmpty()) return null;

        String[] parts = norm.split("\\s+");
        if (parts.length == 0) return null;

        String baseFirst = stripNamespace(parts[0]);

        switch (baseFirst) {
            case "execute", "return", "schedule" -> {
                int runIdx = indexOf(parts, "run");
                if (runIdx >= 0 && runIdx + 1 < parts.length) {
                    MatchResult matched = resolveWithNested(reconstruct(parts, runIdx + 1), depth + 1);
                    if (matched != null) return matched;
                } else if (baseFirst.equals("schedule") && parts.length > 1) {
                    MatchResult matched = resolveWithNested(reconstruct(parts, 1), depth + 1);
                    if (matched != null) return matched;
                }
            }
            case "function" -> {
                if (parts.length > 1) {
                    MatchResult matched = resolveWithNested(parts[1], depth + 1);
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
                        MatchResult matched = resolveWithNested(nested, depth + 1);
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
        resultCache.clear();
        loadConfigRules();
        loadEntries();
    }

    public @NotNull TtlCache<String, String> getMatchCache() {
        return matchCache;
    }
}
