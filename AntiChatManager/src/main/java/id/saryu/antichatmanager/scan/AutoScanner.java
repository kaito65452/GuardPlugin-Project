package id.saryu.antichatmanager.scan;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.antichatmanager.blacklist.BlacklistManager;
import id.saryu.libcore.permission.PermissionModule;
import id.saryu.libcore.util.StringSanitizer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
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

/**
 * Auto-scanner: scans installed plugins' commands/permissions and LuckPerms
 * groups to auto-blacklist admin-level commands. Only commands that actually
 * exist on the server are ever added, so the blacklist stays meaningful.
 */
public class AutoScanner {

    public static final class ScanResult {
        public final String command;
        public final String pluginName;
        public final String permission;
        public final String reason;
        public final BlacklistManager.Source source;
        public final boolean added;

        ScanResult(String command, String pluginName, String permission, String reason,
                   BlacklistManager.Source source, boolean added) {
            this.command = command;
            this.pluginName = pluginName;
            this.permission = permission;
            this.reason = reason;
            this.source = source;
            this.added = added;
        }
    }

    private final AntiChatManager plugin;
    private final Set<String> scannedPlugins = ConcurrentHashMap.newKeySet();
    private final List<ScanResult> lastReport = new ArrayList<>();
    private final Map<String, Integer> lastCountByPlugin = new LinkedHashMap<>();
    private long lastScan = 0;
    private boolean enabled;
    private boolean dryRun;
    private boolean heuristicEnabled;
    private int scanIntervalMinutes = 30;
    private String defaultGroup = "default";
    private int staffMinWeight = 50;
    private final Set<String> excludePlugins = new HashSet<>();
    private final Set<String> excludeCommands = new HashSet<>();
    private final Set<String> excludePermissions = new HashSet<>();
    private org.bukkit.scheduler.BukkitTask timerTask;
    private volatile boolean stopped;

    /**
     * Vanilla commands that are admin-only but may not be exposed through the
     * legacy command map on modern Paper (they live in Brigadier).
     */
    private static final Set<String> VANILLA_ADMIN = Set.of(
            "advancement", "attribute", "ban", "ban-ip", "banlist", "bossbar", "clear", "clone",
            "damage", "data", "datapack", "debug", "deop", "difficulty", "effect", "enchant",
            "execute", "experience", "fill", "forceload", "function", "gamemode", "gamerule",
            "give", "item", "jfr", "kick", "kill", "locate", "loot", "op", "pardon", "pardon-ip",
            "particle", "place", "playsound", "profile", "random", "recipe", "reload", "return",
            "ride", "save-all", "save-off", "save-on", "schedule", "scoreboard", "seed",
            "setblock", "setidletimeout", "setworldspawn", "spawnpoint", "spectate", "spreadplayers",
            "stop", "summon", "tag", "team", "teleport", "tellraw", "tick", "time", "title", "tp",
            "transfer", "weather", "whitelist", "worldborder", "xp"
    );

    private static final Set<String> BUKKIT_ADMIN = Set.of(
            "plugins", "pl", "reload", "timings", "version", "ver", "paper", "mspt", "tps"
    );

    public AutoScanner(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        enabled = plugin.getConfigModule().getBoolean("auto-blacklist.enabled", true);
        dryRun = plugin.getConfigModule().getBoolean("auto-blacklist.dry-run", false);
        heuristicEnabled = plugin.getConfigModule().getBoolean("auto-blacklist.heuristic-enabled", true);
        var ab = plugin.getConfigModule().get().getConfigurationSection("auto-blacklist");
        if (ab != null) {
            var lp = ab.getConfigurationSection("luckperms");
            if (lp != null) {
                scanIntervalMinutes = lp.getInt("scan-interval-minutes", 30);
                defaultGroup = lp.getString("default-group", "default");
                staffMinWeight = lp.getInt("staff-min-weight", 50);
            }
            var excl = ab.getConfigurationSection("exclude");
            if (excl != null) {
                excludePlugins.clear();
                excludeCommands.clear();
                excludePermissions.clear();
                excludeCommands.add("acm");
                excludeCommands.add("antichatmanager");
                excludeCommands.add("saryulib");
                if (excl.isList("plugins")) excludePlugins.addAll(excl.getStringList("plugins"));
                if (excl.isList("commands")) excludeCommands.addAll(lower(excl.getStringList("commands")));
                if (excl.isList("permissions")) excludePermissions.addAll(excl.getStringList("permissions"));
            }
        }
    }

    private Set<String> lower(List<String> values) {
        Set<String> set = new HashSet<>();
        for (String v : values) {
            if (v != null) set.add(stripSlash(v.toLowerCase(Locale.ROOT)));
        }
        return set;
    }

    public void start() {
        if (!enabled) {
            plugin.getLogger().info("Auto-blacklist is disabled in config.yml.");
            return;
        }
        reloadConfig();
        stopped = false;
        // Wait for the initial database load so the scan cannot insert duplicates
        // that already exist in storage. Runs one tick later; never blocks onEnable.
        plugin.getBlacklist().whenLoaded().thenRun(() ->
                plugin.getLib().getSchedulerModule().runSyncLater(() -> {
                    if (!stopped) scan();
                }, 1L));
        long periodTicks = Math.max(20L * 60L, scanIntervalMinutes * 60L * 20L);
        timerTask = plugin.getLib().getSchedulerModule().runAsyncTimer(() -> {
            if (System.currentTimeMillis() - lastScan >= scanIntervalMinutes * 60000L) {
                plugin.getLib().getSchedulerModule().runSync(this::scan);
            }
        }, periodTicks, Math.min(periodTicks, 1200L));
    }

    public void stop() {
        stopped = true;
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
    }

    /**
     * Run a scan. Safe to call from the main thread; database writes are async.
     */
    public void scan() {
        if (stopped || !enabled) return;
        if (!plugin.getBlacklist().isLoaded()) {
            plugin.getLogger().info("Auto-scan postponed until the blacklist has finished loading.");
            return;
        }
        lastScan = System.currentTimeMillis();
        lastReport.clear();
        lastCountByPlugin.clear();

        CommandMap commandMap = Bukkit.getServer().getCommandMap();
        Map<String, Command> primary = new LinkedHashMap<>();
        for (Command cmd : commandMap.getKnownCommands().values()) {
            primary.putIfAbsent(cmd.getName().toLowerCase(Locale.ROOT), cmd);
        }

        int added = 0;
        int considered = 0;

        for (Plugin pl : Bukkit.getPluginManager().getPlugins()) {
            String pluginName = pl.getName();
            if (isOwnPlugin(pluginName)) continue;
            if (excludePlugins.contains(pluginName)) continue;

            Set<String> adminPerms = plugin.getLib().getPermissionModule().scanPluginPermissions(pl);
            int pluginAdded = 0;

            for (Command cmd : primary.values()) {
                if (!(cmd instanceof PluginCommand pc)) continue;
                if (pc.getPlugin() != pl) continue;
                String permission = cmd.getPermission();
                if (permission == null || permission.isBlank()) {
                    continue; // open to everyone -> member level
                }
                String ownerName = pc.getPlugin().getName();
                if (excludePermissions.contains(permission)) continue;

                boolean admin = adminPerms.contains(permission)
                        || plugin.getLib().getPermissionModule().isAdminPermission(permission);
                if (!admin) continue;
                if (!heuristicEnabled && !adminPerms.contains(permission)) continue;

                String command = cmd.getName().toLowerCase(Locale.ROOT);
                if (excludeCommands.contains(command)) continue;
                considered++;
                if (plugin.getBlacklist().contains(command)) continue;

                String reason = "Admin-level permission '" + permission + "' (owner=" + ownerName + ")";
                register(command, BlacklistManager.Source.PLUGIN_SCAN, ownerName, permission, reason, pluginName);
                pluginAdded++;
                added++;
            }

            scannedPlugins.add(pluginName);
            if (pluginAdded > 0) lastCountByPlugin.put(pluginName, pluginAdded);
        }

        // Vanilla / Bukkit server commands are admin-only by default. On modern
        // Paper many of them are Brigadier-only, so a built-in list is used as a
        // fallback in addition to whatever is exposed through the command map.
        int vanillaAdded = 0;
        Set<String> handledVanilla = new HashSet<>();
        boolean vanillaEnabled = plugin.getConfigModule().getBoolean("auto-blacklist.vanilla-enabled", true);

        for (Command cmd : primary.values()) {
            if (!vanillaEnabled) break;
            if (cmd instanceof PluginCommand) continue;
            String command = cmd.getName().toLowerCase(Locale.ROOT);
            String permission = cmd.getPermission();
            boolean isVanilla = VANILLA_ADMIN.contains(command);
            boolean isBukkit = BUKKIT_ADMIN.contains(command)
                    || (permission != null && permission.toLowerCase(Locale.ROOT).startsWith("bukkit.command."));
            if (!isVanilla && !isBukkit) continue;
            handledVanilla.add(command);
            if (excludeCommands.contains(command)) continue;
            if (permission != null && excludePermissions.contains(permission)) continue;
            considered++;
            if (plugin.getBlacklist().contains(command)) continue;
            String perm = permission != null ? permission : "minecraft.command." + command;
            String reason = "Server admin command (permission " + perm + ")";
            register(command, BlacklistManager.Source.PLUGIN_SCAN, "Minecraft", perm, reason, "Minecraft");
            vanillaAdded++;
            added++;
        }

        if (vanillaEnabled) {
            for (String command : VANILLA_ADMIN) {
                if (handledVanilla.contains(command)) continue;
                if (excludeCommands.contains(command)) continue;
                considered++;
                if (plugin.getBlacklist().contains(command)) continue;
                String perm = "minecraft.command." + command;
                String reason = "Vanilla admin command (permission " + perm + ")";
                register(command, BlacklistManager.Source.PLUGIN_SCAN, "Minecraft", perm, reason, "Minecraft");
                vanillaAdded++;
                added++;
            }
        }
        if (vanillaAdded > 0) lastCountByPlugin.put("Minecraft", vanillaAdded);

        // LuckPerms: permissions held only by staff groups.
        if (plugin.getLib().getPermissionModule().hasLuckPerms()) {
            int lpAdded = 0;
            Set<String> staffPerms = plugin.getLib().getPermissionModule()
                    .scanLuckPermsStaffPermissions(defaultGroup, staffMinWeight);
            for (String perm : staffPerms) {
                if (excludePermissions.contains(perm)) continue;
                String command = permToCommand(perm);
                if (command == null) continue;
                if (!primary.containsKey(command)) continue;   // only real commands
                if (excludeCommands.contains(command)) continue;
                if (plugin.getBlacklist().contains(command)) continue;
                register(command, BlacklistManager.Source.LUCKPERMS_SCAN, "LuckPerms", perm,
                        "Permission only held by staff groups", "LuckPerms");
                lpAdded++;
                added++;
            }
            if (lpAdded > 0) lastCountByPlugin.put("LuckPerms", lpAdded);
        }

        plugin.getStorage().recordScan(null, null, null, dryRun ? "scan-dry-run" : "scan", added);

        if (plugin.getConfigModule().getBoolean("debug", false)) {
            plugin.getLogger().info("[debug] Auto-scan: " + primary.size() + " primary commands, "
                    + plugin.getServer().getPluginManager().getPlugins().length + " plugins, "
                    + considered + " admin candidates, " + added + " added, dry-run=" + dryRun);
        }

        if (dryRun) {
            plugin.getLogger().info("Auto-scan (dry-run) complete: " + considered
                    + " admin command(s) detected, " + added + " would be added.");
        } else {
            plugin.getLogger().info("Auto-scan complete: " + considered
                    + " admin command(s) detected, " + added + " new blacklist entries, "
                    + plugin.getBlacklist().count(null) + " total.");
        }

        if (added > 0) notifyStaff(added);
    }

    private boolean isOwnPlugin(String name) {
        return name.equalsIgnoreCase(plugin.getName())
                || name.equalsIgnoreCase("SaryuLibCore");
    }

    private void register(String command, BlacklistManager.Source source, String pluginName,
                          String permission, String reason, String scannedPlugin) {
        plugin.getStorage().recordScan(scannedPlugin, command, permission, source.name(), 1);
        if (dryRun) {
            lastReport.add(new ScanResult(command, pluginName, permission, reason, source, false));
            return;
        }
        BlacklistManager.Entry e = new BlacklistManager.Entry();
        e.command = command;
        e.normalized = StringSanitizer.normalize(command);
        e.mode = BlacklistManager.MatchMode.EXACT;
        e.source = source;
        e.pluginName = pluginName;
        e.permission = permission;
        e.reason = reason;
        e.addedBy = "Auto-Scan";
        e.addedAt = System.currentTimeMillis();
        e.enabled = true;
        plugin.getBlacklist().addEntry(e);
        lastReport.add(new ScanResult(command, pluginName, permission, reason, source, true));
    }

    /**
     * Convert a permission to a candidate command. Only used as a lookup key;
     * the caller verifies the command exists.
     */
    public @Nullable String permToCommand(@NotNull String perm) {
        String p = perm.toLowerCase(Locale.ROOT).trim();
        if (p.isEmpty() || p.equals("*") || p.contains(".*")) return null;
        if (p.startsWith("minecraft.command.")) p = p.substring("minecraft.command.".length());
        else if (p.startsWith("bukkit.command.")) p = p.substring("bukkit.command.".length());
        else if (p.startsWith("command.")) p = p.substring("command.".length());
        int dot = p.lastIndexOf('.');
        if (dot >= 0) p = p.substring(dot + 1);
        p = p.replace('_', '-');
        return p.isEmpty() ? null : p;
    }

    private void notifyStaff(int added) {
        String msg = plugin.getLang().message("scan.staff-alert",
                "&aAuto-scan found &7{count}&a new command(s) to blacklist.")
                .replace("{count}", String.valueOf(added));
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("saryuantichatmanager.alerts")) {
                p.sendMessage(plugin.getMsg().parse(msg));
            }
        }
    }

    private String stripSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isDryRun() { return dryRun; }
    public void scanNow() { scan(); }
    public long getLastScan() { return lastScan; }
    public @NotNull List<ScanResult> getLastReport() { return List.copyOf(lastReport); }
    public @NotNull Map<String, Integer> getLastCountByPlugin() { return Map.copyOf(lastCountByPlugin); }
}
