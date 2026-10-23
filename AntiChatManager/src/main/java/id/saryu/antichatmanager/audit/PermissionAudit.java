package id.saryu.antichatmanager.audit;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PermissionAudit {

    public enum Risk { INFO, MEDIUM, HIGH, CRITICAL }

    public static class Finding {
        public long id;
        public String subjectType;
        public String subjectName;
        public String node;
        public String origin;
        public Risk risk = Risk.INFO;
        public String status = "OPEN";
        public long firstSeen;
        public long lastSeen;
    }

    private final AntiChatManager plugin;
    private final Map<String, Finding> findings = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong nextId = new java.util.concurrent.atomic.AtomicLong(1);
    private org.bukkit.scheduler.BukkitTask reminderTask;
    private boolean enabled = true;
    private long notifyIntervalMs = 6L * 60L * 60L * 1000L;
    private Risk minRisk = Risk.MEDIUM;
    private final Set<String> ignoreGroups = new HashSet<>();
    private final Set<String> ignoreUsers = new HashSet<>();
    private final Set<String> ignorePerms = new HashSet<>();
    private final Set<String> staffGroups = new HashSet<>();
    private final Set<String> riskyPluginPerms = new HashSet<>(Set.of(
            "worldedit.*", "worldguard.*", "worldguard.region.bypass.*", "fawe.*",
            "worldedit.", "worldguard.region.bypass.", "fawe."));
    private final Set<String> dangerous = new HashSet<>(Set.of(
            "op", "deop", "stop", "reload", "execute", "gamemode", "give", "ban", "kick", "lp", "luckperms"));

    public PermissionAudit(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
        reloadConfig();
        loadFromDb();
    }

    public void reloadConfig() {
        enabled = plugin.getConfigModule().getBoolean("audit.enabled", true);
        String interval = plugin.getConfigModule().getString("audit.notify-interval", "6h");
        notifyIntervalMs = parseDuration(interval);
        String risk = plugin.getConfigModule().getString("audit.min-risk-to-notify", "MEDIUM");
        try { minRisk = Risk.valueOf(risk.toUpperCase(Locale.ROOT)); } catch (Exception e) { minRisk = Risk.MEDIUM; }
        ignoreGroups.clear();
        ignoreUsers.clear();
        ignorePerms.clear();
        staffGroups.clear();
        ignoreGroups.addAll(lower(plugin.getConfigModule().getStringList("audit.ignore-groups")));
        ignoreUsers.addAll(lower(plugin.getConfigModule().getStringList("audit.ignore-users")));
        ignorePerms.addAll(lower(plugin.getConfigModule().getStringList("audit.ignore-permissions")));
        staffGroups.addAll(lower(plugin.getConfigModule().getStringList("audit.staff-groups")));
        for (String node : plugin.getConfigModule().getStringList("audit.plugin-permissions")) {
            if (node != null && !node.isBlank()) riskyPluginPerms.add(node.toLowerCase(Locale.ROOT));
        }
    }

    private Set<String> lower(List<String> values) {
        Set<String> set = new HashSet<>();
        if (values == null) return set;
        for (String v : values) if (v != null) set.add(v.toLowerCase(Locale.ROOT));
        return set;
    }

    private long parseDuration(String s) {
        if (s == null || s.equals("0")) return 0;
        try { return new id.saryu.libcore.punishment.DurationParser().parse(s).millis; }
        catch (Exception e) { return 6L * 60L * 60L * 1000L; }
    }

    private void loadFromDb() {
        plugin.getStorage().loadAuditFindings().thenAccept(list -> plugin.getLib().getSchedulerModule().runSync(() -> {
            for (Finding f : list) {
                findings.put(key(f), f);
                nextId.updateAndGet(v -> Math.max(v, f.id + 1));
            }
        }));
    }

    public void start() {
        reloadConfig();
        if (!enabled) return;
        if (!plugin.getLib().getPermissionModule().hasLuckPerms()) {
            plugin.getLogger().info("Permission audit disabled (LuckPerms not present).");
            return;
        }
        if (plugin.getConfigModule().getBoolean("audit.scan-on-start", true)) {
            plugin.getLib().getSchedulerModule().runSyncLater(this::scan, 80L);
        }
        if (notifyIntervalMs > 0) {
            if (reminderTask != null) reminderTask.cancel();
            long ticks = Math.max(20L * 60L, notifyIntervalMs / 50L);
            reminderTask = plugin.getLib().getSchedulerModule().runAsyncTimer(() -> {
                List<Finding> open = getOpen();
                List<Finding> toNotify = new ArrayList<>();
                for (Finding f : open) if (rank(f.risk) >= rank(minRisk)) toNotify.add(f);
                if (!toNotify.isEmpty()) {
                    String msg = plugin.getLang().message("audit.reminder",
                            "&c[Security Audit] {count} permission finding(s) need review. Run &f/acm audit&7.")
                            .replace("{count}", String.valueOf(toNotify.size()));
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
        List<PermissionModule.HolderSnapshot> users = pm.snapshotUsers();
        int defaultWeight = 0;
        for (PermissionModule.HolderSnapshot g : groups) if (g.defaultGroup) defaultWeight = Math.max(defaultWeight, g.weight);
        Set<String> seen = new HashSet<>();
        checkHolders(groups, defaultGroup, defaultWeight, seen);
        if (plugin.getConfigModule().getBoolean("audit.check-users", false)) checkHolders(users, defaultGroup, defaultWeight, seen);
        for (Map.Entry<String, Finding> e : new ArrayList<>(findings.entrySet())) {
            if (!seen.contains(e.getKey())) {
                Finding f = e.getValue();
                if ("OPEN".equals(f.status) || "ACKNOWLEDGED".equals(f.status)) {
                    f.status = "RESOLVED";
                    plugin.getStorage().saveAuditFinding(f);
                }
            }
        }
    }

    private void checkHolders(List<PermissionModule.HolderSnapshot> holders, String defaultGroup, int defaultWeight, Set<String> seen) {
        for (PermissionModule.HolderSnapshot h : holders) {
            if (h.type.equals("GROUP") && ignoreGroups.contains(h.name.toLowerCase(Locale.ROOT))) continue;
            if (h.type.equals("USER") && ignoreUsers.contains(h.identifier.toLowerCase(Locale.ROOT))) continue;
            // Bypass nodes
            String[] bypassNodes = {"saryuantichatmanager.bypass", "saryuantichatmanager.*", "*"};
            for (String node : bypassNodes) {
                if (h.grants(node) && !ignorePerms.contains(node)) {
                    if (h.type.equals("GROUP") && h.name.equalsIgnoreCase(defaultGroup)) {
                        record(h, node, "group:" + h.name, Risk.CRITICAL, seen);
                    } else if (h.type.equals("GROUP") && h.weight <= defaultWeight) {
                        record(h, node, "group:" + h.name, Risk.HIGH, seen);
                    } else {
                        record(h, node, "group:" + h.name, Risk.MEDIUM, seen);
                    }
                }
            }
            // WorldEdit/FAWE/WorldGuard administration permissions. These are
            // high impact even when no command is currently blacklisted.
            Set<String> pluginNodes = new LinkedHashSet<>();
            for (String node : riskyPluginPerms) {
                if (isIgnoredPermission(node)) continue;
                for (Map.Entry<String, Boolean> granted : h.nodes.entrySet()) {
                    if (granted.getKey() == null || !Boolean.TRUE.equals(granted.getValue())) continue;
                    String key = granted.getKey().toLowerCase(Locale.ROOT);
                    boolean match = key.equals(node)
                            || (node.endsWith(".*") && key.startsWith(node.substring(0, node.length() - 1)))
                            || (node.endsWith(".") && key.startsWith(node));
                    if (match) pluginNodes.add(key);
                }
                if (h.grants(node)) pluginNodes.add(node);
            }
            for (String node : pluginNodes) {
                if (isIgnoredPermission(node)) continue;
                boolean staff = h.type.equals("GROUP") && staffGroups.contains(h.name.toLowerCase(Locale.ROOT));
                Risk r = staff ? Risk.HIGH : Risk.CRITICAL;
                String origin = "luckperms:" + h.type.toLowerCase(Locale.ROOT) + ":" + h.name
                        + " (weight=" + h.weight + ", primary=" + h.primaryGroup + ")"
                        + (staff ? " [staff-group]" : " [non-staff]");
                record(h, node, origin, r, seen);
            }

            // Direct permission for blacklisted commands
            for (BlacklistManager.Entry entry : plugin.getBlacklist().getAllEntries()) {
                if (entry == null || entry.permission == null || entry.permission.isBlank()) continue;
                if (ignorePerms.contains(entry.permission)) continue;
                if (!h.grants(entry.permission)) continue;
                if (h.hasBypass("saryuantichatmanager.bypass")) continue;
                String cmd = plugin.getBlacklist().stripNamespace(entry.command).toLowerCase(Locale.ROOT);
                Risk r = Risk.INFO;
                if (h.type.equals("GROUP") && (h.defaultGroup || h.weight <= defaultWeight)) r = dangerous.contains(cmd) ? Risk.CRITICAL : Risk.HIGH;
                else if (dangerous.contains(cmd)) r = Risk.HIGH;
                else r = Risk.MEDIUM;
                record(h, entry.permission, "blacklist:" + entry.source.name(), r, seen);
            }
        }
    }

    private boolean isIgnoredPermission(String node) {
        if (node == null) return false;
        String value = node.toLowerCase(Locale.ROOT);
        if (ignorePerms.contains(value)) return true;
        for (String ignored : ignorePerms) {
            if (ignored.endsWith(".*")) {
                String prefix = ignored.substring(0, ignored.length() - 1);
                if (value.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    private void record(PermissionModule.HolderSnapshot h, String node, String origin, Risk risk, Set<String> seen) {
        String k = key(h.type, h.name, node);
        seen.add(k);
        Finding f = findings.computeIfAbsent(k, key -> {
            Finding nf = new Finding();
            nf.subjectType = h.type;
            nf.subjectName = h.name;
            nf.node = node;
            nf.origin = origin;
            nf.firstSeen = System.currentTimeMillis();
            nf.status = "OPEN";
            nf.id = nextId.getAndIncrement();
            return nf;
        });
        f.risk = risk;
        f.origin = origin;
        f.lastSeen = System.currentTimeMillis();
        if ("RESOLVED".equals(f.status)) f.status = "OPEN";
        findings.put(k, f);
        plugin.getStorage().saveAuditFinding(f);
    }

    private String key(PermissionModule.HolderSnapshot h, String node) {
        return key(h.type, h.name, node);
    }

    private String key(String type, String name, String node) {
        return (type == null ? "" : type) + "|" + (name == null ? "" : name.toLowerCase(Locale.ROOT)) + "|" + (node == null ? "" : node);
    }

    private String key(Finding f) {
        return key(f.subjectType, f.subjectName, f.node);
    }

    private int rank(Risk r) {
        return switch (r) {
            case CRITICAL -> 4;
            case HIGH -> 3;
            case MEDIUM -> 2;
            case INFO -> 1;
        };
    }

    public @NotNull List<Finding> getOpen() {
        List<Finding> list = new ArrayList<>();
        for (Finding f : findings.values()) if ("OPEN".equals(f.status) || "ACKNOWLEDGED".equals(f.status)) list.add(f);
        list.sort((a, b) -> {
            int ra = rank(b.risk) - rank(a.risk);
            if (ra != 0) return ra;
            return a.subjectName.compareToIgnoreCase(b.subjectName);
        });
        return list;
    }

    public @Nullable Finding get(long id) {
        for (Finding f : findings.values()) if (f.id == id) return f;
        return null;
    }

    public void acknowledge(long id) {
        Finding f = get(id);
        if (f == null) return;
        f.status = "ACKNOWLEDGED";
        plugin.getStorage().saveAuditFinding(f);
    }

    /** Backwards-compatible helper: {@code user=true} ignores a user, otherwise a group. */
    public void ignore(String target, boolean user) {
        ignore(target, user ? "user" : "group");
    }

    /**
     * Add a group, user or permission node to the ignore list. Existing findings
     * that match are marked IGNORED so they disappear from {@link #getOpen()}.
     */
    public void ignore(@Nullable String target, @NotNull String type) {
        if (target == null || target.isBlank()) return;
        String value = target.trim().toLowerCase(Locale.ROOT);
        switch (type.toLowerCase(Locale.ROOT)) {
            case "user" -> ignoreUsers.add(value);
            case "node", "permission", "perm" -> ignorePerms.add(value);
            default -> ignoreGroups.add(value);
        }
        saveIgnores();
        for (Finding f : findings.values()) {
            boolean match = switch (type.toLowerCase(Locale.ROOT)) {
                case "user" -> f.subjectType.equalsIgnoreCase("USER")
                        && f.subjectName != null && f.subjectName.equalsIgnoreCase(target);
                case "node", "permission", "perm" -> f.node != null && f.node.equalsIgnoreCase(target);
                default -> f.subjectType.equalsIgnoreCase("GROUP")
                        && f.subjectName != null && f.subjectName.equalsIgnoreCase(target);
            };
            if (match && !"RESOLVED".equals(f.status)) {
                f.status = "IGNORED";
                plugin.getStorage().saveAuditFinding(f);
            }
        }
    }

    private void saveIgnores() {
        var cfg = plugin.getConfigModule().get();
        cfg.set("audit.ignore-groups", new ArrayList<>(ignoreGroups));
        cfg.set("audit.ignore-users", new ArrayList<>(ignoreUsers));
        cfg.set("audit.ignore-permissions", new ArrayList<>(ignorePerms));
        plugin.getConfigModule().save();
    }

    public boolean isEnabled() {
        return enabled && plugin.getLib().getPermissionModule().hasLuckPerms();
    }
}
