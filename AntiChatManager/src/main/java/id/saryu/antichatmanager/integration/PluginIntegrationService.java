package id.saryu.antichatmanager.integration;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.antichatmanager.blacklist.BlacklistManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Maps sensitive plugins to their registered commands, permissions and command
 * listeners, and owns the optional WorldEdit/FAWE/WorldGuard integration.
 *
 * <p>All plugin names from config are treated as data. They are never evaluated,
 * compiled as regex, or used to build shell commands.</p>
 */
public final class PluginIntegrationService implements Listener {

    public static final class Mapping {
        public final String pluginName;
        public final String version;
        public final boolean installed;
        public final Set<String> commands = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        public final Set<String> commandKeys = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        public final Set<String> permissions = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        public final Map<String, String> commandPermissions = new LinkedHashMap<>();
        public final List<String> listeners = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        Mapping(@NotNull String pluginName, @Nullable String version, boolean installed) {
            this.pluginName = pluginName;
            this.version = version == null ? "" : version;
            this.installed = installed;
        }

        public boolean hasCommand(@NotNull String command) {
            return commands.contains(command.toLowerCase(Locale.ROOT));
        }
    }

    public static final class Recommendation {
        public final String plugin;
        public final String command;
        public final String reason;
        public final boolean verifiedInCommandMap;

        Recommendation(@NotNull String plugin, @NotNull String command, @NotNull String reason,
                       boolean verifiedInCommandMap) {
            this.plugin = plugin;
            this.command = command;
            this.reason = reason;
            this.verifiedInCommandMap = verifiedInCommandMap;
        }
    }

    public static final class ApplyResult {
        public int added;
        public int skipped;
        public int selected;
        public final List<String> addedCommands = new ArrayList<>();
    }

    private static final int MAX_MANUAL_PLUGINS = 64;
    private static final int MAX_PLUGIN_NAME = 64;
    private static final int MAX_COMMAND = 128;
    private static final int MAX_CONFIG_BYTES = 2 * 1024 * 1024;
    private static final Set<String> INTEGRATION_NAMES = Set.of(
            "worldedit", "fastasyncworldedit", "fawe", "worldguard");
    private static final List<String> WE_DOCUMENTED = List.of(
            "we report", "schem load", "schem save", "brush", "regen", "set", "replace");
    private static final List<String> WG_DOCUMENTED = List.of(
            "wg reload", "wg report", "rg define", "rg remove", "rg flag",
            "rg addowner", "rg addmember");

    private final AntiChatManager plugin;
    private volatile Map<String, Mapping> mappings = Map.of();
    private volatile List<Recommendation> recommendations = List.of();
    private volatile List<String> configWarnings = List.of();
    private volatile List<String> manualPlugins = List.of();
    private volatile boolean integrationEnabled = true;
    private volatile boolean compatMode = true;
    private volatile long lastScan;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();
    private boolean started;

    public PluginIntegrationService(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void start() {
        started = true;
        plugin.getBlacklist().whenLoaded().thenRun(() ->
                plugin.getLib().getSchedulerModule().runSyncLater(() -> scanAsync(null), 20L));
    }

    public void stop() {
        started = false;
        HandlerList.unregisterAll(this);
    }

    public void reloadConfig() {
        integrationEnabled = plugin.getConfigModule().getBoolean("integration.enabled", true);
        compatMode = plugin.getConfigModule().getBoolean("integration.compat-mode", true);
        List<String> configured = plugin.getConfigModule().getStringList("plugin-manual-scan");
        List<String> cleaned = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : configured) {
            if (cleaned.size() >= MAX_MANUAL_PLUGINS) {
                plugin.getLogger().warning("plugin-manual-scan has more than "
                        + MAX_MANUAL_PLUGINS + " entries; extra entries were ignored.");
                break;
            }
            if (raw == null) continue;
            String name = raw.trim();
            if (name.isEmpty() || name.length() > MAX_PLUGIN_NAME) {
                plugin.getLogger().warning("Ignoring invalid plugin-manual-scan entry (empty or longer than "
                        + MAX_PLUGIN_NAME + " characters).");
                continue;
            }
            boolean control = false;
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c < 0x20 || c == 0x7f) { control = true; break; }
            }
            if (control) {
                plugin.getLogger().warning("Ignoring plugin-manual-scan entry containing control characters.");
                continue;
            }
            if (seen.add(name.toLowerCase(Locale.ROOT))) cleaned.add(name);
        }
        manualPlugins = List.copyOf(cleaned);
    }

    public boolean isStarted() { return started; }
    public boolean isScanning() { return scanning.get(); }
    public boolean isIntegrationEnabled() { return integrationEnabled; }
    public boolean isCompatMode() { return compatMode; }
    public long getLastScan() { return lastScan; }
    public @NotNull List<String> getManualPlugins() { return manualPlugins; }
    public @NotNull List<String> getConfigWarnings() { return configWarnings; }
    public @NotNull List<Recommendation> getRecommendations() { return recommendations; }
    public @NotNull Collection<Mapping> getMappings() {
        List<Mapping> list = new ArrayList<>(mappings.values());
        list.sort(Comparator.comparing(m -> m.pluginName.toLowerCase(Locale.ROOT)));
        return list;
    }

    public @Nullable Mapping getMapping(@NotNull String name) {
        return mappings.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Map one plugin on demand for {@code /acm scan <plugin>}. If the plugin is
     * in {@code plugin-manual-scan}, its command labels are also blacklisted;
     * otherwise the result is a read-only preview and no rule is written.
     */
    public void scanPluginAsync(@NotNull String requested,
                                @NotNull java.util.function.Consumer<@Nullable Mapping> callback) {
        plugin.getLib().getSchedulerModule().runAsync(() -> {
            Mapping mapping = null;
            try {
                Plugin target = Bukkit.getPluginManager().getPlugin(requested);
                if (target == null) {
                    warnMissing(requested);
                } else {
                    warnedMissing.remove(requested.toLowerCase(Locale.ROOT));
                    CommandMap commandMap = Bukkit.getServer().getCommandMap();
                    mapping = mapPlugin(target, commandMap, false);
                    boolean configured = manualPlugins.stream()
                            .anyMatch(n -> n.equalsIgnoreCase(target.getName()));
                    if (configured) applyManualMapping(mapping);
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "On-demand plugin scan failed", t);
            }
            final Mapping result = mapping;
            plugin.getLib().getSchedulerModule().runSync(() -> {
                if (result != null) {
                    Map<String, Mapping> updated = new LinkedHashMap<>(mappings);
                    updated.put(result.pluginName.toLowerCase(Locale.ROOT), result);
                    mappings = Map.copyOf(updated);
                }
                callback.accept(result);
            });
        });
    }

    /** Add the command labels for one installed, configured plugin. */
    private void applyManualMapping(@NotNull Mapping mapping) {
        for (String command : mapping.commands) {
            String normalized = plugin.getBlacklist().canonicalize(command);
            if (normalized.isBlank()) continue;
            BlacklistManager.Entry existing = plugin.getBlacklist().findExactEntry(normalized);
            if (existing != null) continue;
            BlacklistManager.Entry entry = new BlacklistManager.Entry();
            entry.command = normalized;
            entry.normalized = normalized;
            entry.mode = BlacklistManager.MatchMode.EXACT;
            entry.source = BlacklistManager.Source.MANUAL_SCAN;
            entry.action = BlacklistManager.Action.BLOCK;
            entry.pluginName = mapping.pluginName;
            entry.permission = mapping.commandPermissions.get(normalized);
            entry.reason = "manual-scan:" + mapping.pluginName;
            entry.addedBy = "manual-scan";
            entry.addedAt = System.currentTimeMillis();
            plugin.getBlacklist().addScannedEntry(entry);
        }
    }

    /** Trigger a full async scan; the callback runs on the main thread. */
    public void scanAsync(@Nullable Runnable callback) {
        if (!scanning.compareAndSet(false, true)) return;
        plugin.getLib().getSchedulerModule().runAsync(() -> {
            Map<String, Mapping> found = new LinkedHashMap<>();
            List<String> warnings = new ArrayList<>();
            try {
                CommandMap commandMap = Bukkit.getServer().getCommandMap();
                for (String configured : manualPlugins) {
                    Plugin target = Bukkit.getPluginManager().getPlugin(configured);
                    if (target == null) {
                        warnMissing(configured);
                        Mapping missing = new Mapping(configured, "", false);
                        missing.warnings.add("not installed; will retry on PluginEnableEvent");
                        found.put(configured.toLowerCase(Locale.ROOT), missing);
                        continue;
                    }
                    warnedMissing.remove(configured.toLowerCase(Locale.ROOT));
                    found.put(target.getName().toLowerCase(Locale.ROOT),
                            mapPlugin(target, commandMap, false));
                }

                if (integrationEnabled) {
                    for (String name : List.of("WorldEdit", "FastAsyncWorldEdit", "WorldGuard")) {
                        Plugin target = Bukkit.getPluginManager().getPlugin(name);
                        if (target == null) continue;
                        found.put(target.getName().toLowerCase(Locale.ROOT),
                                mapPlugin(target, commandMap, true));
                    }
                }

                List<Recommendation> recs = integrationEnabled
                        ? buildRecommendations(found) : List.of();
                if (integrationEnabled
                        && plugin.getConfigModule().getBoolean("integration.read-plugin-configs", true)) {
                    warnings.addAll(readPluginConfigWarnings(found));
                }
                applyManualMappings(found);
                final Map<String, Mapping> publishMappings = Map.copyOf(found);
                final List<Recommendation> publishRecs = List.copyOf(recs);
                final List<String> publishWarnings = List.copyOf(warnings);
                plugin.getLib().getSchedulerModule().runSync(() -> {
                    mappings = publishMappings;
                    recommendations = publishRecs;
                    configWarnings = publishWarnings;
                    lastScan = System.currentTimeMillis();
                    scanning.set(false);
                    if (callback != null) {
                        try { callback.run(); } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Plugin integration scan failed", t);
                scanning.set(false);
                plugin.getLib().getSchedulerModule().runSync(() -> {
                    if (callback != null) {
                        try { callback.run(); } catch (Throwable ignored) {}
                    }
                });
            }
        });
    }

    private void warnMissing(@NotNull String configured) {
        String key = configured.toLowerCase(Locale.ROOT);
        if (warnedMissing.add(key)) {
            plugin.getLogger().warning("plugin-manual-scan: plugin '" + configured
                    + "' was not found; will retry when it is enabled.");
        }
    }

    private Mapping mapPlugin(@NotNull Plugin target, @NotNull CommandMap commandMap,
                              boolean integration) {
        Mapping mapping = new Mapping(target.getName(), target.getDescription().getVersion(), true);
        try {
            for (Map.Entry<String, Command> entry : commandMap.getKnownCommands().entrySet()) {
                Command command = entry.getValue();
                if (command == null) continue;
                if (!(command instanceof PluginIdentifiableCommand identifiable)) continue;
                Plugin owner = identifiable.getPlugin();
                if (owner == null || !owner.getName().equalsIgnoreCase(target.getName())) continue;
                addCommand(mapping, entry.getKey());
                addCommand(mapping, command.getName());
                for (String alias : command.getAliases()) addCommand(mapping, alias);
                String commandPermission = command.getPermission();
                if (commandPermission != null && !commandPermission.isBlank()) {
                    addPermissions(mapping, commandPermission);
                    addCommandPermission(mapping, entry.getKey(), commandPermission);
                    addCommandPermission(mapping, command.getName(), commandPermission);
                    for (String alias : command.getAliases()) {
                        addCommandPermission(mapping, alias, commandPermission);
                    }
                }
                String namespace = target.getName().toLowerCase(Locale.ROOT) + ":";
                String key = entry.getKey() == null ? "" : entry.getKey();
                if (key.toLowerCase(Locale.ROOT).startsWith(namespace)) {
                    mapping.commandKeys.add(key.toLowerCase(Locale.ROOT));
                }
            }
        } catch (Throwable t) {
            mapping.warnings.add("command map unavailable: " + t.getMessage());
        }

        PluginDescriptionFile description = target.getDescription();
        Map<String, Map<String, Object>> declared = description.getCommands();
        if (declared != null) {
            for (Map.Entry<String, Map<String, Object>> declaredCommand : declared.entrySet()) {
                addCommand(mapping, declaredCommand.getKey());
                Map<String, Object> meta = declaredCommand.getValue();
                if (meta == null) continue;
                Object declaredPermission = meta.get("permission");
                if (declaredPermission instanceof String permission && !permission.isBlank()) {
                    addPermissions(mapping, permission);
                    addCommandPermission(mapping, declaredCommand.getKey(), permission);
                    Object aliases = meta.get("aliases");
                    if (aliases instanceof Collection<?> aliasList) {
                        for (Object alias : aliasList) {
                            if (alias != null) addCommandPermission(mapping, String.valueOf(alias), permission);
                        }
                    }
                }
            }
        }
        try {
            for (org.bukkit.permissions.Permission permission : description.getPermissions()) {
                if (permission != null && permission.getName() != null) {
                    addPermissions(mapping, permission.getName());
                    for (String child : permission.getChildren().keySet()) addPermissions(mapping, child);
                }
            }
        } catch (Throwable t) {
            mapping.warnings.add("permission metadata unavailable: " + t.getMessage());
        }

        try {
            for (RegisteredListener listener : PlayerCommandPreprocessEvent.getHandlerList().getRegisteredListeners()) {
                if (listener.getPlugin() != null
                        && listener.getPlugin().getName().equalsIgnoreCase(target.getName())) {
                    if (mapping.listeners.size() < 20) {
                        mapping.listeners.add(listener.getListener().getClass().getName()
                                + "@" + listener.getPriority().name());
                    }
                }
            }
        } catch (Throwable t) {
            mapping.warnings.add("listener metadata unavailable: " + t.getMessage());
        }

        if (compatMode && !mapping.permissions.isEmpty()) {
            mapping.warnings.add("compat mode: plugin already enforces "
                    + mapping.permissions.size() + " permission node(s)");
        }
        if (integration) {
            mapping.warnings.add("integration mode: recommendations are opt-in");
        }
        return mapping;
    }

    private void addCommand(@NotNull Mapping mapping, @Nullable String raw) {
        if (raw == null) return;
        String cleaned = raw.trim().toLowerCase(Locale.ROOT);
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        if (cleaned.isBlank() || cleaned.length() > MAX_COMMAND) return;
        if (cleaned.indexOf(' ') >= 0) return;
        mapping.commandKeys.add(cleaned);
        String canonical = plugin.getBlacklist().canonicalize(cleaned);
        // Some plugins expose multi-line/compound permission metadata. Keep
        // cache values bounded and never treat them as executable input.
        if (!canonical.isBlank() && canonical.length() <= MAX_COMMAND
                && canonical.indexOf('/') < 0 && canonical.indexOf(' ') < 0) {
            mapping.commands.add(canonical);
        }
    }

    private void addCommandPermission(@NotNull Mapping mapping, @Nullable String raw,
                                      @Nullable String rawPermission) {
        if (raw == null || rawPermission == null) return;
        String command = plugin.getBlacklist().canonicalize(raw);
        if (command.isBlank() || command.indexOf(' ') >= 0 || command.length() > MAX_COMMAND) return;
        for (String part : rawPermission.toLowerCase(Locale.ROOT).split("[;,\\s]+")) {
            String node = part.trim();
            if (node.isBlank() || node.length() > 128) continue;
            if (!node.matches("[a-z0-9_.*+\\-]+")) continue;
            mapping.commandPermissions.putIfAbsent(command, node);
            return;
        }
    }

    private void addPermissions(@NotNull Mapping mapping, @Nullable String raw) {
        if (raw == null) return;
        for (String part : raw.toLowerCase(Locale.ROOT).split("[;,\\s]+")) {
            String node = part.trim();
            if (node.isBlank() || node.length() > 128) continue;
            if (!node.matches("[a-z0-9_.*+\\-]+")) continue;
            mapping.permissions.add(node);
        }
    }

    private List<Recommendation> buildRecommendations(@NotNull Map<String, Mapping> found) {
        Map<String, Recommendation> byCommand = new LinkedHashMap<>();
        Mapping we = firstInstalled(found, "WorldEdit", "FastAsyncWorldEdit");
        if (we != null) {
            for (String command : WE_DOCUMENTED) {
                boolean verified = hasBase(we, command);
                byCommand.putIfAbsent(command, new Recommendation(we.pluginName, command,
                        "documented " + we.pluginName + " command"
                                + (verified ? " (verified in CommandMap)" : " (documented fallback)"),
                        verified));
            }
        }
        Mapping fawe = found.get("fastasyncworldedit");
        if (fawe != null) {
            addRec(byCommand, fawe, "fawe", "FAWE administration command");
            addRec(byCommand, fawe, "schem load", "FAWE schematic load");
            addRec(byCommand, fawe, "schem save", "FAWE schematic save");
            addRec(byCommand, fawe, "brush", "FAWE brush command");
            addRec(byCommand, fawe, "regen", "FAWE region regeneration");
        }
        Mapping wg = found.get("worldguard");
        if (wg != null) {
            for (String command : WG_DOCUMENTED) {
                boolean verified = hasBase(wg, command);
                addRec(byCommand, wg, command, "WorldGuard management command"
                        + (verified ? " (verified in CommandMap)" : " (documented fallback)"));
            }
        }
        return List.copyOf(byCommand.values());
    }

    private void addRec(@NotNull Map<String, Recommendation> out, @NotNull Mapping mapping,
                        @NotNull String command, @NotNull String reason) {
        out.putIfAbsent(command, new Recommendation(mapping.pluginName, command, reason,
                hasBase(mapping, command)));
    }

    private boolean hasBase(@NotNull Mapping mapping, @NotNull String command) {
        int space = command.indexOf(' ');
        String base = space >= 0 ? command.substring(0, space) : command;
        return mapping.hasCommand(base);
    }

    private @Nullable Mapping firstInstalled(@NotNull Map<String, Mapping> found, String... names) {
        for (String name : names) {
            Mapping mapping = found.get(name.toLowerCase(Locale.ROOT));
            if (mapping != null && mapping.installed) return mapping;
        }
        return null;
    }

    /**
     * Persist all currently installed sensitive plugins. Existing manual/config
     * rules are left untouched; manual-scan rows for removed plugins or removed
     * commands are disabled (not deleted).
     */
    private void applyManualMappings(@NotNull Map<String, Mapping> found) {
        Set<String> desired = new LinkedHashSet<>();
        Set<String> installedPlugins = new LinkedHashSet<>();
        for (Mapping mapping : found.values()) {
            if (!mapping.installed) continue;
            if (!manualPlugins.stream().anyMatch(n -> n.equalsIgnoreCase(mapping.pluginName))) continue;
            installedPlugins.add(mapping.pluginName.toLowerCase(Locale.ROOT));
            for (String command : mapping.commands) {
                String normalized = plugin.getBlacklist().canonicalize(command);
                if (!normalized.isBlank()) desired.add(normalized);
            }
            applyManualMapping(mapping);
        }
        List<BlacklistManager.Entry> current = new ArrayList<>(plugin.getBlacklist().getAllEntries());
        for (BlacklistManager.Entry entry : current) {
            if (entry.source != BlacklistManager.Source.MANUAL_SCAN) continue;
            String owner = entry.pluginName == null ? "" : entry.pluginName.toLowerCase(Locale.ROOT);
            boolean configured = manualPlugins.stream().anyMatch(n -> n.equalsIgnoreCase(owner));
            boolean keep = configured && installedPlugins.contains(owner)
                    && desired.contains(entry.normalized);
            if (!keep && entry.id > 0) {
                plugin.getBlacklist().disableEntry(entry.id);
            }
        }
    }

    private List<String> readPluginConfigWarnings(@NotNull Map<String, Mapping> found) {
        List<String> out = new ArrayList<>();
        for (Mapping mapping : found.values()) {
            if (!mapping.installed) continue;
            if (!INTEGRATION_NAMES.contains(mapping.pluginName.toLowerCase(Locale.ROOT))) continue;
            Plugin target = Bukkit.getPluginManager().getPlugin(mapping.pluginName);
            if (target == null) continue;
            File file = new File(target.getDataFolder(), "config.yml");
            if (!file.isFile()) continue;
            try {
                if (Files.size(file.toPath()) > MAX_CONFIG_BYTES) {
                    out.add(mapping.pluginName + ": config.yml is larger than 2 MiB; skipped");
                    continue;
                }
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                int inspected = 0;
                for (String line : lines) {
                    if (++inspected > 20000) break;
                    String trimmed = line.trim();
                    String lower = trimmed.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("#") || lower.isEmpty()) continue;
                    if ((lower.contains("max-blocks-changed") || lower.contains("max-radius")
                            || lower.contains("max-brush-radius") || lower.contains("max-claim-volume")
                            || lower.contains("max-region-count"))
                            && (lower.endsWith(": -1") || lower.endsWith(": 0")
                            || lower.contains("unlimited") || lower.endsWith(": true"))) {
                        out.add(mapping.pluginName + ": " + trimmed);
                        if (out.size() >= 20) return out;
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE,
                        "Could not read " + mapping.pluginName + " config for audit", t);
            }
        }
        return out;
    }

    /**
     * Apply opt-in recommendations to the admin-managed commands section.
     * Manual/allow rules are never overwritten.
     */
    public @NotNull ApplyResult applyRecommendations(@NotNull String selector) {
        ApplyResult result = new ApplyResult();
        String wanted = selector.trim().toLowerCase(Locale.ROOT);
        if (!wanted.equals("all") && !wanted.equals("worldedit") && !wanted.equals("we")
                && !wanted.equals("fawe") && !wanted.equals("fastasyncworldedit")
                && !wanted.equals("worldguard") && !wanted.equals("wg")) {
            throw new IllegalArgumentException("unknown integration: " + selector);
        }
        var config = plugin.getConfigModule().get();
        var section = config.getConfigurationSection("commands");
        if (section == null) section = config.createSection("commands");
        for (Recommendation rec : recommendations) {
            String pluginName = rec.plugin.toLowerCase(Locale.ROOT);
            boolean match = wanted.equals("all")
                    || (wanted.equals("worldedit") || wanted.equals("we"))
                    && (pluginName.contains("worldedit") || pluginName.contains("fawe"))
                    || (wanted.equals("fawe") || wanted.equals("fastasyncworldedit"))
                    && pluginName.contains("fawe")
                    || (wanted.equals("worldguard") || wanted.equals("wg"))
                    && pluginName.contains("worldguard");
            if (!match) continue;
            result.selected++;
            if (section.isConfigurationSection(rec.command)
                    || plugin.getBlacklist().hasExactEntry(rec.command)) {
                result.skipped++;
                continue;
            }
            var rule = section.createSection(rec.command);
            rule.set("mode", "block");
            rule.set("reason", rec.reason);
            result.added++;
            result.addedCommands.add(rec.command);
        }
        if (result.added > 0) {
            plugin.getConfigModule().save();
            plugin.getBlacklist().reloadConfigRules();
        }
        return result;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(@NotNull PluginEnableEvent event) {
        if (!started) return;
        String name = event.getPlugin().getName();
        boolean configured = manualPlugins.stream().anyMatch(n -> n.equalsIgnoreCase(name));
        if (configured || INTEGRATION_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            plugin.getLib().getSchedulerModule().runSyncLater(() -> scanAsync(null), 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(@NotNull PluginDisableEvent event) {
        if (!started) return;
        String name = event.getPlugin().getName();
        boolean relevant = manualPlugins.stream().anyMatch(n -> n.equalsIgnoreCase(name))
                || INTEGRATION_NAMES.contains(name.toLowerCase(Locale.ROOT));
        if (!relevant) return;
        // Disable immediately; a delayed rescan can otherwise still see the
        // plugin's commands during the disable/registration teardown window.
        plugin.getBlacklist().disableManualScanEntriesForPlugin(name);
        plugin.getLib().getSchedulerModule().runSyncLater(() -> scanAsync(null), 20L);
    }
}
