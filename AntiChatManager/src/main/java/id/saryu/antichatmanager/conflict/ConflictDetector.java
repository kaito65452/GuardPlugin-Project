package id.saryu.antichatmanager.conflict;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.antichatmanager.blacklist.BlacklistManager;
import id.saryu.libcore.permission.PermissionModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ConflictDetector {

    public enum Severity { INFO, REVIEW, CRITICAL }

    public static class Conflict {
        public long id;
        public String command;
        public String groupName;
        public String permission;
        public Severity severity = Severity.INFO;
        public String source;
        public long firstSeen;
        public long lastSeen;
        public String status = "OPEN";
    }

    private final AntiChatManager plugin;
    private final Map<String, Conflict> conflicts = new ConcurrentHashMap<>();
    private org.bukkit.scheduler.BukkitTask reminderTask;
    private boolean enabled = true;
    private int staffMinWeight = 50;
    private long reminderIntervalMs = 6L * 60L * 60L * 1000L;
    private final Set<String> dangerous = new HashSet<>();
    private final Set<String> ignore = new HashSet<>();
    private final Map<String, Set<String>> allowedGroups = new LinkedHashMap<>();

    public ConflictDetector(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
        reloadConfig();
        loadFromDb();
    }

    public void reloadConfig() {
        enabled = plugin.getConfigModule().getBoolean("conflict-detection.enabled", true);
        staffMinWeight = plugin.getConfigModule().getInt("conflict-detection.staff-min-weight", 50);
        String interval = plugin.getConfigModule().getString("conflict-detection.reminder-interval", "6h");
        reminderIntervalMs = parseDuration(interval);
        dangerous.clear();
        dangerous.addAll(lower(plugin.getConfigModule().getStringList("conflict-detection.dangerous-commands")));
        ignore.clear();
        ignore.addAll(lower(plugin.getConfigModule().getStringList("conflict-detection.ignore")));
        allowedGroups.clear();
        var section = plugin.getConfigModule().get().getConfigurationSection("conflict-detection.allowed-groups");
        if (section != null) {
            for (String cmd : section.getKeys(false)) {
                Set<String> groups = new HashSet<>(lowerList(section.getStringList(cmd)));
                if (!groups.isEmpty()) allowedGroups.put(cmd.toLowerCase(Locale.ROOT), groups);
            }
        }
    }

    private List<String> lowerList(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) return out;
        for (String v : values) if (v != null) out.add(v.toLowerCase(Locale.ROOT));
        return out;
    }

    private Set<String> lower(List<String> values) {
        return new HashSet<>(lowerList(values));
    }

    private long parseDuration(String s) {
        if (s == null || s.equals("0")) return 0;
        try {
            return new id.saryu.libcore.punishment.DurationParser().parse(s).millis;
        } catch (Exception e) {
            return 6L * 60L * 60L * 1000L;
        }
    }

    private void loadFromDb() {
        plugin.getStorage().loadConflicts().thenAccept(list -> plugin.getLib().getSchedulerModule().runSync(() -> {
            for (Conflict c : list) conflicts.put(key(c.command, c.groupName, c.permission), c);
        }));
    }

    public void start() {
        reloadConfig();
        if (!enabled) return;
        if (!plugin.getLib().getPermissionModule().hasLuckPerms()) {
            plugin.getLogger().info("Conflict detection disabled (LuckPerms not present).");
            return;
        }
        plugin.getLib().getSchedulerModule().runSyncLater(this::scan, 60L);
        if (reminderIntervalMs > 0) {
            if (reminderTask != null) reminderTask.cancel();
            long ticks = Math.max(20L * 60L, reminderIntervalMs / 50L);
            reminderTask = plugin.getLib().getSchedulerModule().runAsyncTimer(() -> {
                List<Conflict> open = getOpen();
                if (!open.isEmpty()) {
                    String msg = plugin.getLang().message("conflicts.reminder",
                            "&c[Security] {count} LuckPerms conflict(s) remain open. Run &f/acm conflicts&7 to review (blacklist will NOT be removed automatically).")
                            .replace("{count}", String.valueOf(open.size()));
                    plugin.getLib().getSchedulerModule().runSync(() -> {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (p.hasPermission("saryuantichatmanager.alerts")) {
                                p.sendMessage(plugin.getMsg().parse(msg));
                            }
                        }
                    });
                }
            }, ticks, ticks);
        }
        if (plugin.getConfigModule().getBoolean("conflict-detection.notify-staff-on-join", true)) {
            // Join notifications are handled by PlayerListener if needed.
        }
    }

    public void stop() {
        if (reminderTask != null) {
            reminderTask.cancel();
            reminderTask = null;
        }
    }

    public void scan() {
        if (!enabled || !plugin.getLib().getPermissionModule().hasLuckPerms()) return;
        PermissionModule pm = plugin.getLib().getPermissionModule();
        String defaultGroup = plugin.getConfigModule().getString("auto-blacklist.luckperms.default-group", "default");
        List<PermissionModule.HolderSnapshot> groups = pm.snapshotGroups(defaultGroup);
        Map<String, Integer> weight = new LinkedHashMap<>();
        int defaultWeight = 0;
        for (PermissionModule.HolderSnapshot g : groups) {
            weight.put(g.name.toLowerCase(Locale.ROOT), g.weight);
            if (g.defaultGroup) defaultWeight = Math.max(defaultWeight, g.weight);
        }
        Set<String> seen = new HashSet<>();
        for (BlacklistManager.Entry entry : plugin.getBlacklist().getAllEntries()) {
            if (entry == null || !entry.enabled) continue;
            String cmd = plugin.getBlacklist().stripNamespace(entry.command).toLowerCase(Locale.ROOT);
            if (cmd.isBlank()) continue;
            String ignoreKey = cmd;
            if (ignore.contains(ignoreKey)) continue;
            for (PermissionModule.HolderSnapshot g : groups) {
                String gKey = g.name.toLowerCase(Locale.ROOT);
                if (ignore.contains(ignoreKey + ":" + gKey)) continue;
                if (isAllowed(cmd, gKey)) continue;
                // Permission node this command likely needs.
                String perm = entry.permission;
                boolean hasPerm = perm != null && !perm.isBlank() ? g.grants(perm) : false;
                if (!hasPerm) continue;
                if (g.hasBypass("saryuantichatmanager.bypass")) continue;
                Severity sev = severity(g, cmd, gKey.equals(defaultGroup.toLowerCase(Locale.ROOT)), weight.getOrDefault(gKey, 0), defaultWeight);
                String skey = key(cmd, g.name, perm == null ? "" : perm);
                Conflict c = conflicts.computeIfAbsent(skey, k -> {
                    Conflict nc = new Conflict();
                    nc.command = cmd;
                    nc.groupName = g.name;
                    nc.permission = perm;
                    nc.source = entry.source.name();
                    nc.firstSeen = System.currentTimeMillis();
                    nc.status = "OPEN";
                    return nc;
                });
                c.groupName = g.name;
                c.permission = perm;
                c.source = entry.source.name();
                c.severity = sev;
                c.lastSeen = System.currentTimeMillis();
                if (!"IGNORED".equals(c.status)) c.status = "OPEN";
                conflicts.put(skey, c);
                seen.add(skey);
                plugin.getStorage().saveConflict(c);
            }
        }
        for (Map.Entry<String, Conflict> e : new ArrayList<>(conflicts.entrySet())) {
            if (!seen.contains(e.getKey())) {
                Conflict c = e.getValue();
                if (!"IGNORED".equals(c.status)) {
                    c.status = "RESOLVED";
                    plugin.getStorage().saveConflict(c);
                }
            }
        }
    }

    private boolean isAllowed(String cmd, String group) {
        Set<String> groups = allowedGroups.get(cmd);
        return groups != null && groups.contains(group);
    }

    public boolean isAllowedForPlayer(@NotNull String cmd, @NotNull Player player) {
        Set<String> groups = allowedGroups.get(strip(cmd));
        if (groups == null || groups.isEmpty()) return false;
        for (String group : groups) {
            if (player.hasPermission("group." + group)) return true;
        }
        return false;
    }

    private String strip(String cmd) {
        return plugin.getBlacklist().stripNamespace(cmd == null ? "" : cmd).toLowerCase(Locale.ROOT);
    }

    private Severity severity(PermissionModule.HolderSnapshot group, String cmd, boolean isDefault,
                              int weight, int defaultWeight) {
        if (isDefault || weight <= defaultWeight) return Severity.CRITICAL;
        if (dangerous.contains(cmd)) return weight < staffMinWeight ? Severity.CRITICAL : Severity.REVIEW;
        if (weight < staffMinWeight) return Severity.REVIEW;
        return Severity.INFO;
    }

    private String key(String cmd, String group, String perm) {
        return (cmd == null ? "" : cmd) + "|" + (group == null ? "" : group.toLowerCase(Locale.ROOT)) + "|" + (perm == null ? "" : perm);
    }

    public @NotNull List<Conflict> getOpen() {
        List<Conflict> list = new ArrayList<>();
        for (Conflict c : conflicts.values()) if ("OPEN".equals(c.status)) list.add(c);
        list.sort((a, b) -> {
            int sa = rank(a.severity);
            int sb = rank(b.severity);
            if (sa != sb) return sb - sa;
            return a.command.compareTo(b.command);
        });
        return list;
    }

    private int rank(Severity s) {
        return switch (s) {
            case CRITICAL -> 3;
            case REVIEW -> 2;
            case INFO -> 1;
        };
    }

    public void ignore(String cmd, @Nullable String group) {
        if (group == null || group.isBlank()) {
            ignore.add(cmd.toLowerCase(Locale.ROOT));
        } else {
            ignore.add(cmd.toLowerCase(Locale.ROOT) + ":" + group.toLowerCase(Locale.ROOT));
        }
        for (Conflict c : conflicts.values()) {
            if (c.command.equalsIgnoreCase(cmd) && (group == null || group.isBlank() || c.groupName.equalsIgnoreCase(group))) {
                c.status = "IGNORED";
                plugin.getStorage().saveConflict(c);
            }
        }
    }

    public void whitelistGroup(String cmd, String group) {
        if (group == null || group.isBlank()) return;
        String c = cmd.toLowerCase(Locale.ROOT);
        allowedGroups.computeIfAbsent(c, k -> new HashSet<>()).add(group.toLowerCase(Locale.ROOT));
        // Persist to config for the session.
        var section = plugin.getConfigModule().get().getConfigurationSection("conflict-detection.allowed-groups");
        if (section == null) section = plugin.getConfigModule().get().createSection("conflict-detection.allowed-groups");
        List<String> list = section.getStringList(cmd);
        if (!list.stream().anyMatch(x -> x.equalsIgnoreCase(group))) {
            list.add(group);
            section.set(cmd, list);
            plugin.getConfigModule().save();
        }
        for (Conflict conflict : conflicts.values()) {
            if (conflict.command.equalsIgnoreCase(c) && conflict.groupName.equalsIgnoreCase(group) && "OPEN".equals(conflict.status)) {
                conflict.status = "RESOLVED";
                plugin.getStorage().saveConflict(conflict);
            }
        }
    }

    public @NotNull Map<String, Conflict> getAll() {
        return conflicts;
    }

    public boolean isEnabled() {
        return enabled && plugin.getLib().getPermissionModule().hasLuckPerms();
    }
}
