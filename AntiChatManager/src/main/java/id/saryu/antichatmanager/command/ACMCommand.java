package id.saryu.antichatmanager.command;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.antichatmanager.audit.PermissionAudit;
import id.saryu.antichatmanager.blacklist.BlacklistManager;
import id.saryu.antichatmanager.conflict.ConflictDetector;
import id.saryu.antichatmanager.identity.PluginIdentityService;
import id.saryu.antichatmanager.scan.AutoScanner;
import id.saryu.antichatmanager.security.SecurityAudit;
import id.saryu.antichatmanager.storage.Storage;
import id.saryu.libcore.command.CommandFramework;
import id.saryu.libcore.util.StringSanitizer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The /acm command. Subcommands are registered into SaryuLibCore's
 * {@link CommandFramework} as a plugin-owned root; the actual Bukkit command is
 * declared in plugin.yml so aliases and permissions work out of the box.
 *
 * <p>Subcommands: help, reload, info, scan, blacklist, history, violations,
 * template, language, export, import.</p>
 */
public class ACMCommand {

    public static final String PERM_ADMIN = "saryuantichatmanager.admin";
    private static final int PAGE_SIZE = 10;

    private final AntiChatManager plugin;

    public ACMCommand(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
    }

    public void register(@NotNull CommandFramework framework) {
        // Subcommands enforce their own permissions. This allows dedicated
        // integration/subcmd administrators without granting full /acm access.
        CommandFramework.Root root = framework.root("acm", "",
                "AntiChatManager administration");
        root.register(new HelpSub());
        root.register(new ReloadSub());
        root.register(new InfoSub());
        root.register(new ScanSub());
        root.register(new BlacklistSub());
        root.register(new PluginsSub(plugin));
        root.register(new IntegrationSub(plugin));
        root.register(new SubcmdSub(plugin));
        root.register(new ConflictsSub(plugin));
        root.register(new AuditSub(plugin));
        root.register(new SecuritySub(plugin));
        root.register(new HistorySub());
        root.register(new ViolationsSub());
        root.register(new TemplateSub());
        root.register(new LanguageSub());
        root.register(new ExportSub());
        root.register(new ImportSub());
        root.messages(new Messages());
        root.bind(plugin);
    }

    /* ------------------------------------------------------------------ */
    /* Framework messages                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Sends every framework-level message through AntiChatManager's language
     * files and the shared MessageModule, so legacy codes, hex colours,
     * MiniMessage tags and gradients are always parsed before output.
     */
    private final class Messages implements CommandFramework.MessageSink {
        @Override
        public void unknownSubcommand(CommandSender sender, String label, String input,
                                      @Nullable String suggestion) {
            send(sender, "command.unknown-subcommand", "&cUnknown subcommand: &7{sub}",
                    "sub", input);
            if (suggestion != null && !suggestion.isBlank()) {
                send(sender, "command.did-you-mean", "&7Did you mean &f{suggestion}&7?",
                        "suggestion", suggestion);
            }
        }

        @Override
        public void usage(CommandSender sender, String label, String usage) {
            send(sender, "command.usage", "&7Usage: &f{usage}", "usage", usage);
        }

        @Override
        public void subcommands(CommandSender sender, String label,
                                java.util.Collection<String> subcommands) {
            send(sender, "command.subcommands", "&7Subcommands: &f{subcommands}",
                    "subcommands", String.join(", ", subcommands));
        }

        @Override
        public void noPermission(CommandSender sender, boolean subcommand) {
            send(sender, subcommand ? "command.no-sub-permission" : "command.no-permission",
                    subcommand ? "&cYou do not have permission to use this subcommand."
                            : "&cYou do not have permission to use this command.");
        }

        @Override
        public void playerOnly(CommandSender sender) {
            send(sender, "command.player-only",
                    "&cThis command can only be used by a player.");
        }

        @Override
        public void cooldown(CommandSender sender) {
            send(sender, "command.cooldown",
                    "&cPlease wait before using this command again.");
        }

        @Override
        public void internalError(CommandSender sender, String label, String subcommand,
                                  Throwable error) {
            send(sender, "command.internal-error",
                    "&cAn internal error occurred. Check the console.");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Message pipeline                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * Single rendering path for /acm output. Language text is expanded, values
     * are escaped against MiniMessage injection, {@code %prefix%} is replaced by
     * the configured prefix, and the result is parsed by MessageModule.
     *
     * @param addPrefix prepend the prefix when the template does not contain
     *                  {@code %prefix%} itself
     */
    private Component render(String raw, boolean addPrefix, Object... vars) {
        String text = fill(raw, vars);
        String prefix = plugin.getConfigModule().getString("prefix", "");
        if (prefix == null || prefix.isBlank()) {
            prefix = plugin.getLang().get("prefix", "&7[&bACM&7]");
        }
        text = text.contains("%prefix%")
                ? text.replace("%prefix%", prefix)
                : (addPrefix ? prefix + " " + text : text);
        return plugin.getMsg().parse(text);
    }

    /** Send a prefixed, language-aware message. */
    private void send(CommandSender sender, String key, String def, Object... vars) {
        sender.sendMessage(render(plugin.getLang().message(key, def), true, vars));
    }

    /** Send a continuation line (header/list row) without forcing the prefix. */
    private void sendRaw(CommandSender sender, String key, String def, Object... vars) {
        sender.sendMessage(render(plugin.getLang().message(key, def), false, vars));
    }

    private String fill(String text, Object... vars) {
        if (text == null) return "";
        String out = text;
        for (int i = 0; i + 1 < vars.length; i += 2) {
            out = out.replace("{" + vars[i] + "}", plugin.getMsg().escape(String.valueOf(vars[i + 1])));
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Base                                                               */
    /* ------------------------------------------------------------------ */

    private abstract class Base implements CommandFramework.SubCommand {
        @Override public @NotNull String permission() { return PERM_ADMIN; }
        @Override public boolean playerOnly() { return false; }
        @Override public int cooldownSeconds() { return 0; }
        @Override public @NotNull List<String> tabComplete(@NotNull CommandSender sender, String[] args) {
            return List.of();
        }

        protected boolean isInt(String value) {
            try {
                Integer.parseInt(value);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /** @return matching source or null when the value is a plugin/page. */
        protected BlacklistManager.Source parseSource(String value) {
            for (BlacklistManager.Source source : BlacklistManager.Source.values()) {
                if (source.name().equalsIgnoreCase(value)) return source;
            }
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* help                                                               */
    /* ------------------------------------------------------------------ */

    private final class HelpSub extends Base {
        @Override public @NotNull String name() { return "help"; }
        @Override public String description() { return "Show command list"; }
        @Override public String usage() { return "/acm help"; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            sendRaw(sender, "help.header", "&b=== AntiChatManager Commands ===");
            Set<String> shown = new HashSet<>();
            for (String row : plugin.getLang().messageList("help.rows")) {
                // Sub-command hints themselves contain '|' (e.g. <id|command>),
                // so the description follows the last separator.
                int split = row.lastIndexOf('|');
                if (split < 0) continue;
                String sub = row.substring(0, split).trim();
                shown.add(sub.split("\\s+", 2)[0].toLowerCase(Locale.ROOT));
                sendRaw(sender, "help.line", "&7/{cmd} &f{sub} &8- &7{desc}",
                        "cmd", label,
                        "sub", sub,
                        "desc", row.substring(split + 1));
            }
            // A stale on-disk language file must never hide a registered command.
            CommandFramework.Root root = plugin.getLib().getCommandFramework().getRoot("acm");
            if (root != null) {
                for (CommandFramework.SubCommand sub : root.getSubCommands().values()) {
                    String name = sub.name().toLowerCase(Locale.ROOT);
                    if (!shown.add(name)) continue;
                    String desc = plugin.getLang().message("help.sub." + name, sub.description());
                    sendRaw(sender, "help.line", "&7/{cmd} &f{sub} &8- &7{desc}",
                            "cmd", label, "sub", sub.name(), "desc", desc);
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* reload                                                             */
    /* ------------------------------------------------------------------ */

    private final class ReloadSub extends Base {
        @Override public @NotNull String name() { return "reload"; }
        @Override public String description() { return "Reload configuration"; }
        @Override public String usage() { return "/acm reload"; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            plugin.reloadAll();
            send(sender, "reload", "&aAntiChatManager configuration reloaded.");
        }
    }

    /* ------------------------------------------------------------------ */
    /* info                                                               */
    /* ------------------------------------------------------------------ */

    private final class InfoSub extends Base {
        @Override public @NotNull String name() { return "info"; }
        @Override public String description() { return "Show plugin status"; }
        @Override public String usage() { return "/acm info"; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            Map<BlacklistManager.Source, Integer> counts = plugin.getBlacklist().countBySource();
            sendRaw(sender, "info.title", "&b=== AntiChatManager v{version} ===",
                    "version", plugin.getBuildInfo().getVersion());
            sendRaw(sender, "info.build", "&7Build: &f{build}",
                    "build", plugin.getBuildInfo().display());
            sendRaw(sender, "info.db", "&7Database: &f{type} &7(available={available}, schema=v{schema})",
                    "type", plugin.getLib().getDatabaseModule().getType(),
                    "available", plugin.getLib().getDatabaseModule().isAvailable(),
                    "schema", plugin.getLib().getDatabaseModule().getSchemaVersion());
            sendRaw(sender, "info.entries",
                    "&7Blacklist entries: &f{count} &7(manual={manual}, plugin={plugin}, luckperms={lp}, import={imp})",
                    "count", plugin.getBlacklist().count(null),
                    "manual", counts.getOrDefault(BlacklistManager.Source.MANUAL, 0),
                    "plugin", counts.getOrDefault(BlacklistManager.Source.PLUGIN_SCAN, 0),
                    "lp", counts.getOrDefault(BlacklistManager.Source.LUCKPERMS_SCAN, 0),
                    "imp", counts.getOrDefault(BlacklistManager.Source.IMPORT, 0));
            sendRaw(sender, "info.packet", "&7Packet layer: &f{backend} &7(active={active})",
                    "backend", plugin.getLib().getPacketLayer().getBackendName(),
                    "active", plugin.getLib().getPacketLayer().isAvailable());
            sendRaw(sender, "info.luckperms", "&7LuckPerms: &f{available}",
                    "available", plugin.getLib().getPermissionModule().hasLuckPerms());
            sendRaw(sender, "info.language", "&7Language: &f{code} &7(per-player={perplayer})",
                    "code", plugin.getLang().getActiveCode(),
                    "perplayer", plugin.getLang().isPerPlayer());
            sendRaw(sender, "info.scan", "&7Auto-scan: &f{enabled} &7(dry-run={dry}, last={last})",
                    "enabled", plugin.getAutoScanner().isEnabled(),
                    "dry", plugin.getAutoScanner().isDryRun(),
                    "last", plugin.getAutoScanner().getLastScan() == 0
                            ? plugin.getLang().message("info.never", "never")
                            : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                            .format(new Date(plugin.getAutoScanner().getLastScan())));
            sendRaw(sender, "info.provider", "&7Ban provider: &f{provider}",
                    "provider", plugin.getLib().getPunishmentService().getProviderType());
            String jarHash = plugin.getSecurityAudit().selfHash();
            if (jarHash != null) {
                sendRaw(sender, "info.jarhash", "&7Plugin JAR SHA-256: &f{hash}",
                        "hash", jarHash);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* scan                                                               */
    /* ------------------------------------------------------------------ */

    private final class ScanSub extends Base {
        @Override public @NotNull String name() { return "scan"; }
        @Override public String description() { return "Run or inspect the auto-scan"; }
        @Override public String usage() { return "/acm scan [report|sources|identify|list|<plugin>]"; }

        @Override
        public @NotNull List<String> tabComplete(@NotNull CommandSender sender, String[] args) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of("report", "sources", "identify", "list"));
                if (plugin.getPluginIntegrationService() != null) {
                    options.addAll(plugin.getPluginIntegrationService().getManualPlugins());
                }
                return filter(options, args[0]);
            }
            return List.of();
        }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length > 0 && args[0].equalsIgnoreCase("list")) {
                listPluginMappings(sender);
                return;
            }
            if (args.length > 0 && !args[0].equalsIgnoreCase("report")
                    && !args[0].equalsIgnoreCase("sources")
                    && !args[0].equalsIgnoreCase("identify")) {
                String requested = args[0];
                if (requested.length() > 64 || requested.isBlank()) {
                    send(sender, "scan.plugin.invalid", "&cInvalid plugin name.");
                    return;
                }
                for (int i = 0; i < requested.length(); i++) {
                    char c = requested.charAt(i);
                    if (c < 0x20 || c == 0x7f) {
                        send(sender, "scan.plugin.invalid", "&cInvalid plugin name.");
                        return;
                    }
                }
                var service = plugin.getPluginIntegrationService();
                if (service == null) {
                    send(sender, "messages.internal-error", "%prefix%&cAn internal error occurred. Check the console.");
                    return;
                }
                send(sender, "scan.plugin.started", "&7Rescanning plugin &f{plugin}&7 asynchronously...",
                        "plugin", requested);
                service.scanPluginAsync(requested, mapping -> {
                    if (mapping == null || !mapping.installed) {
                        send(sender, "scan.plugin.missing", "&7Plugin &f{plugin} &7is not installed; retry will happen on enable.",
                                "plugin", requested);
                        return;
                    }
                    boolean configured = service.getManualPlugins().stream()
                            .anyMatch(n -> n.equalsIgnoreCase(mapping.pluginName));
                    send(sender, configured ? "scan.plugin.done" : "scan.plugin.preview",
                            configured
                                    ? "&aPlugin &f{plugin} &amapped: commands={commands}, permissions={permissions}, listeners={listeners}."
                                    : "&7Plugin &f{plugin} &7mapped (not in plugin-manual-scan; preview only): commands={commands}, permissions={permissions}, listeners={listeners}.",
                            "plugin", mapping.pluginName,
                            "commands", String.valueOf(mapping.commands.size()),
                            "permissions", String.valueOf(mapping.permissions.size()),
                            "listeners", String.valueOf(mapping.listeners.size()));
                });
                return;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("identify")) {
                if (plugin.getPluginIdentityService() == null) {
                    send(sender, "messages.internal-error", "%prefix%&cAn internal error occurred. Check the console.");
                    return;
                }
                plugin.getPluginIdentityService().reloadConfig();
                plugin.getPluginIdentityService().identifyNow();
                send(sender, "plugins.identify.done", "&aPlugin identification complete.");
                return;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("report")) {
                List<AutoScanner.ScanResult> report = plugin.getAutoScanner().getLastReport();
                if (report.isEmpty()) {
                    if (plugin.getAutoScanner().getLastScan() > 0) {
                        send(sender, "scan.report.none",
                                "&7The last scan added no new commands; everything is already blacklisted.");
                    } else {
                        send(sender, "scan.report.empty", "&7No scan report yet. Run &f/acm scan&7.");
                    }
                    return;
                }
                sendRaw(sender, "scan.report.header", "&b=== Last Auto-Scan Report ({count}) ===",
                        "count", report.size());
                report.stream().limit(30).forEach(r -> sendRaw(sender, "scan.report.line",
                        "&7{command} &8[{source}] &7plugin={plugin} perm={permission} added={added}",
                        "command", r.command,
                        "source", r.source,
                        "plugin", r.pluginName,
                        "permission", r.permission,
                        "added", r.added));
                return;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("sources")) {
                Map<String, Integer> byPlugin = plugin.getAutoScanner().getLastCountByPlugin();
                if (byPlugin.isEmpty()) {
                    send(sender, "scan.sources.empty", "&7No commands were added by the last scan.");
                    return;
                }
                sendRaw(sender, "scan.sources.header", "&b=== Auto-Scan Sources ===");
                byPlugin.forEach((name, count) -> sendRaw(sender, "scan.sources.line",
                        "&7{name}: &f{count} new entries", "name", name, "count", count));
                return;
            }
            plugin.getAutoScanner().reloadConfig();
            plugin.getAutoScanner().scanNow();
            send(sender, "scan.done", "&aScan finished: &f{count}&a blacklist entries total.",
                    "count", plugin.getBlacklist().count(null));
        }

        private void listPluginMappings(CommandSender sender) {
            var service = plugin.getPluginIntegrationService();
            if (service == null || service.getMappings().isEmpty()) {
                send(sender, "scan.list.empty", "&7No plugin mappings yet. Run &f/acm scan <plugin>&7.");
                return;
            }
            sendRaw(sender, "scan.list.header", "&b=== Plugin Mapping Results ===");
            for (var mapping : service.getMappings()) {
                boolean manual = service.getManualPlugins().stream()
                        .anyMatch(n -> n.equalsIgnoreCase(mapping.pluginName));
                String source = manual ? "manual-scan:" + mapping.pluginName
                        : (mapping.installed ? "integration" : "manual-scan:" + mapping.pluginName);
                sendRaw(sender, "scan.list.plugin",
                        "&7- &f{plugin} &8v{version} &7installed={installed} source={source}",
                        "plugin", mapping.pluginName, "version", mapping.version,
                        "installed", String.valueOf(mapping.installed), "source", source);
                List<String> commands = new ArrayList<>(mapping.commands);
                String sample = commands.isEmpty() ? "-"
                        : String.join(", ", commands.subList(0, Math.min(12, commands.size())))
                        + (commands.size() > 12 ? " ..." : "");
                sendRaw(sender, "scan.list.commands", "&8    commands({count}): &7{commands}",
                        "count", commands.size(), "commands", sample);
                List<String> perms = new ArrayList<>(mapping.permissions);
                String permSample = perms.isEmpty() ? "-"
                        : String.join(", ", perms.subList(0, Math.min(8, perms.size())))
                        + (perms.size() > 8 ? " ..." : "");
                sendRaw(sender, "scan.list.permissions", "&8    permissions({count}): &7{permissions}",
                        "count", perms.size(), "permissions", permSample);
                if (!mapping.listeners.isEmpty()) {
                    sendRaw(sender, "scan.list.listeners", "&8    listeners: &7{listeners}",
                            "listeners", String.join(", ", mapping.listeners));
                }
                for (String warning : mapping.warnings) {
                    sendRaw(sender, "scan.list.warning", "&8    ! &7{warning}", "warning", warning);
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* blacklist                                                          */
    /* ------------------------------------------------------------------ */

    private final class BlacklistSub extends Base {
        @Override public @NotNull String name() { return "blacklist"; }
        @Override public String description() { return "Manage blacklist entries"; }
        @Override public String usage() { return "/acm blacklist <list|check|info|add|remove|whitelist|purge>"; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length == 0) {
                send(sender, "blacklist.usage",
                        "&7Usage: &f/{label} blacklist <list|check|info|add|remove|whitelist|purge>",
                        "label", label);
                return;
            }
            String action = args[0].toLowerCase(Locale.ROOT);
            switch (action) {
                case "list" -> list(sender, args);
                case "check" -> check(sender, args);
                case "info" -> info(sender, args);
                case "add" -> add(sender, args);
                case "remove", "delete" -> remove(sender, args);
                case "whitelist", "unblacklist" -> whitelist(sender, args);
                case "purge" -> purge(sender);
                default -> send(sender, "blacklist.usage",
                        "&7Usage: &f/{label} blacklist <list|check|info|add|remove|whitelist|purge>",
                        "label", label);
            }
        }

        @Override
        public @NotNull List<String> tabComplete(@NotNull CommandSender sender, String[] args) {
            if (args.length == 1) {
                return filter(List.of("list", "check", "info", "add", "remove", "whitelist", "purge"), args[0]);
            }
            if (args.length == 2) {
                if (args[0].equalsIgnoreCase("list")) {
                    List<String> options = new ArrayList<>();
                    for (BlacklistManager.Source source : BlacklistManager.Source.values()) {
                        options.add(source.name());
                    }
                    options.addAll(plugin.getBlacklist().getPluginNames());
                    return filter(options, args[1]);
                }
                if (args[0].equalsIgnoreCase("check")
                        || args[0].equalsIgnoreCase("info")
                        || args[0].equalsIgnoreCase("remove")
                        || args[0].equalsIgnoreCase("whitelist")) {
                    List<String> commands = new ArrayList<>();
                    for (BlacklistManager.Entry e : plugin.getBlacklist().getSortedEntries()) {
                        if (commands.size() >= 100) break;
                        commands.add(e.command);
                    }
                    return filter(commands, args[1]);
                }
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("whitelist")) {
                return filter(plugin.getLib().getPermissionModule().getLoadedGroupNames(), args[2]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
                List<String> modes = new ArrayList<>();
                for (BlacklistManager.MatchMode m : BlacklistManager.MatchMode.values()) modes.add(m.name());
                return filter(modes, args[2]);
            }
            return List.of();
        }

        private void list(CommandSender sender, String[] args) {
            int page = 1;
            BlacklistManager.Source source = null;
            String pluginFilter = null;
            for (int i = 1; i < args.length; i++) {
                if (isInt(args[i])) {
                    page = Math.max(1, Integer.parseInt(args[i]));
                    continue;
                }
                BlacklistManager.Source candidate = parseSource(args[i]);
                if (candidate != null) {
                    source = candidate;
                } else {
                    pluginFilter = args[i];
                }
            }
            int total = pluginFilter != null
                    ? plugin.getBlacklist().countByPlugin(pluginFilter)
                    : plugin.getBlacklist().count(source);
            int pages = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
            page = Math.min(page, pages);
            List<BlacklistManager.Entry> entries =
                    plugin.getBlacklist().getPage(page, PAGE_SIZE, source, pluginFilter);
            if (entries.isEmpty()) {
                send(sender, "blacklist.list.empty", "&cNo blacklist entries found.");
                return;
            }
            sendRaw(sender, "blacklist.list.header",
                    "&b=== Blacklist Entries (page {page}/{total}) &7- {count} total",
                    "page", page, "total", pages, "count", total);
            for (BlacklistManager.Entry e : entries) {
                sendRaw(sender, "blacklist.list.entry",
                        "&7#{id} &f{command} &7[{mode}/{action}] &8source={source} plugin={plugin} added_by={addedby}",
                        "id", displayId(e.id),
                        "command", e.command,
                        "mode", e.mode,
                        "action", e.action == null ? "BLOCK" : e.action.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                        "source", e.sourceLabel(),
                        "plugin", e.pluginName == null ? "-" : e.pluginName,
                        "addedby", e.addedBy == null ? "-" : e.addedBy);
            }
            sendRaw(sender, "blacklist.list.footer",
                    "&7Use &f/{label} blacklist info <id> &7for details.",
                    "label", "acm");
        }

        private void check(CommandSender sender, String[] args) {
            if (args.length < 2) {
                send(sender, "blacklist.check.usage", "&7Usage: &f/acm blacklist check <command>");
                return;
            }
            String command = normalizeInput(joinArgs(args, 1));
            BlacklistManager.MatchResult result = plugin.getBlacklist().resolve(command);
            if (result == null) {
                send(sender, "blacklist.check.free", "&a&f{command} &ais not blacklisted.",
                        "command", command);
                return;
            }
            if (result.isAllow()) {
                send(sender, "blacklist.check.allowed",
                        "%prefix%&a&f{command} &ahas an explicit allow rule.", "command", command);
                printEntry(sender, result.entry);
                return;
            }
            sendRaw(sender, "blacklist.check.title", "&b=== Blacklist Check: &f{command}",
                    "command", command);
            sendRaw(sender, "blacklist.check.matched", "&7Matched: &f{matched}", "matched", result.command);
            if (result.entry != null) {
                printEntry(sender, result.entry);
            }
        }

        private void info(CommandSender sender, String[] args) {
            if (args.length < 2) {
                send(sender, "blacklist.info.usage", "&7Usage: &f/acm blacklist info <id|command>");
                return;
            }
            BlacklistManager.Entry entry;
            if (isInt(args[1])) {
                entry = plugin.getBlacklist().getEntry(Integer.parseInt(args[1]));
            } else {
                entry = plugin.getBlacklist().findEntry(normalizeInput(joinArgs(args, 1)));
            }
            if (entry == null) {
                send(sender, "blacklist.notfound", "&cNo blacklist entry found for &7{command}.",
                        "command", args[1]);
                return;
            }
            sendRaw(sender, "blacklist.info.title", "&b=== Blacklist Entry #{id} ===",
                    "id", displayId(entry.id));
            printEntry(sender, entry);
        }

        private void printEntry(CommandSender sender, BlacklistManager.Entry entry) {
            line(sender, "blacklist.field.command", "command", entry.command);
            line(sender, "blacklist.field.mode", "mode", entry.mode);
            line(sender, "blacklist.field.action", "action",
                    entry.action == null ? "BLOCK" : entry.action.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
            line(sender, "blacklist.field.source", "source", entry.sourceLabel());
            line(sender, "blacklist.field.plugin", "plugin",
                    entry.pluginName == null ? "-" : entry.pluginName);
            line(sender, "blacklist.field.permission", "permission",
                    entry.permission == null ? "-" : entry.permission);
            line(sender, "blacklist.field.reason", "reason",
                    entry.reason == null ? "-" : entry.reason);
            line(sender, "blacklist.field.world", "world",
                    entry.world == null || entry.world.isBlank() ? "*" : entry.world);
            line(sender, "blacklist.field.allowed_groups", "allowed groups",
                    entry.allowedGroups.isEmpty() ? "-" : String.join(", ", entry.allowedGroups));
            line(sender, "blacklist.field.added_by", "added_by",
                    entry.addedBy == null ? "-" : entry.addedBy);
            line(sender, "blacklist.field.added_at", "added_at", entry.addedAt == 0 ? "-"
                    : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(entry.addedAt)));
        }

        private void line(CommandSender sender, String labelKey, String fallback, Object value) {
            sendRaw(sender, "blacklist.info.line", "&7  {key}: &f{value}",
                    "key", plugin.getLang().message(labelKey, fallback), "value", value);
        }

        private void add(CommandSender sender, String[] args) {
            if (args.length < 2) {
                send(sender, "blacklist.add.usage",
                        "&7Usage: &f/acm blacklist add <command> [mode] [reason...]");
                return;
            }
            String command = normalizeInput(args[1]);
            BlacklistManager.MatchMode mode = BlacklistManager.MatchMode.EXACT;
            int reasonStart = 2;
            if (args.length > 2) {
                try {
                    mode = BlacklistManager.MatchMode.valueOf(args[2].toUpperCase(Locale.ROOT));
                    reasonStart = 3;
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (plugin.getBlacklist().findEntry(command) != null) {
                send(sender, "blacklist.add.exists", "&c&f{command} &cis already blacklisted.",
                        "command", command);
                return;
            }
            StringBuilder reason = new StringBuilder();
            for (int i = reasonStart; i < args.length; i++) {
                if (reason.length() > 0) reason.append(' ');
                reason.append(args[i]);
            }
            BlacklistManager.Entry entry = new BlacklistManager.Entry();
            entry.command = command;
            entry.normalized = plugin.getBlacklist().canonicalize(command);
            entry.mode = mode;
            entry.source = BlacklistManager.Source.MANUAL;
            entry.reason = reason.length() == 0
                    ? fill(plugin.getLang().message("blacklist.add.default-reason",
                            "Added by {player}"), "player", sender.getName())
                    : reason.toString();
            entry.addedBy = sender.getName();
            entry.addedAt = System.currentTimeMillis();
            plugin.getBlacklist().addEntry(entry);
            send(sender, "blacklist.added",
                    "&aAdded &7{command} &ato the blacklist (source: {source}, mode: {mode}).",
                    "command", command, "source", entry.source, "mode", entry.mode);
        }

        private void remove(CommandSender sender, String[] args) {
            if (args.length < 2) {
                send(sender, "blacklist.remove.usage", "&7Usage: &f/acm blacklist remove <id|command>");
                return;
            }
            int removed;
            if (isInt(args[1])) {
                removed = plugin.getBlacklist().removeEntry(Integer.parseInt(args[1])) ? 1 : 0;
            } else {
                removed = plugin.getBlacklist().removeByCommand(normalizeInput(joinArgs(args, 1)), null);
            }
            if (removed == 0) {
                send(sender, "blacklist.notfound", "&cNo blacklist entry found for &7{command}.",
                        "command", args[1]);
            } else {
                send(sender, "blacklist.removed",
                        "&aRemoved &7{count} &aentry/entries for &7{command}&a.",
                        "count", removed, "command", args[1]);
            }
        }

        private void whitelist(CommandSender sender, String[] args) {
            if (args.length < 2) {
                send(sender, "blacklist.whitelist.usage",
                        "&7Usage: &f/acm blacklist whitelist <command> [group]");
                return;
            }
            String command = normalizeInput(args[1]);
            if (args.length >= 3) {
                String group = args[2];
                if (!plugin.getBlacklist().addAllowedGroup(command, group)) {
                    send(sender, "blacklist.notfound", "&cNo blacklist entry found for &7{command}.",
                            "command", command);
                    return;
                }
                plugin.getConflictDetector().whitelistGroup(command, group);
                send(sender, "blacklist.whitelist.group",
                        "&aGroup &f{group} &ais now allowed to use &f{command}&a. "
                        + "The entry stays blacklisted for other groups.",
                        "group", group, "command", command);
                return;
            }
            int removed = plugin.getBlacklist().removeByCommand(command, BlacklistManager.Source.PLUGIN_SCAN)
                    + plugin.getBlacklist().removeByCommand(command, BlacklistManager.Source.LUCKPERMS_SCAN);
            if (removed == 0) {
                send(sender, "blacklist.whitelist.none",
                        "&7No auto-scan entries found for &f{command}&7.", "command", command);
            } else {
                send(sender, "blacklist.whitelist.done",
                        "&aRemoved &f{count} &aauto-scan entries for &f{command}&a. "
                        + "Add it to auto-blacklist.exclude.commands to keep it that way.",
                        "count", removed, "command", command);
            }
        }

        private void purge(CommandSender sender) {
            int removed = 0;
            var commandMap = Bukkit.getServer().getCommandMap();
            for (BlacklistManager.Entry entry : new ArrayList<>(plugin.getBlacklist().getSortedEntries())) {
                if (entry.source != BlacklistManager.Source.PLUGIN_SCAN
                        && entry.source != BlacklistManager.Source.LUCKPERMS_SCAN) {
                    continue;
                }
                String base = plugin.getBlacklist().commandOnly(entry.command);
                if (commandMap.getCommand(base) == null) {
                    if (plugin.getBlacklist().removeEntry(entry.id)) removed++;
                }
            }
            send(sender, "blacklist.purge",
                    "&aPurged &f{count} &aauto-scan entries that no longer match a registered command.",
                    "count", removed);
        }
    }

    /* ------------------------------------------------------------------ */
    /* history                                                            */
    /* ------------------------------------------------------------------ */

    private final class HistorySub extends Base {
        @Override public @NotNull String name() { return "history"; }
        @Override public String description() { return "Show violation history"; }
        @Override public String usage() { return "/acm history <player> [page]"; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length < 1) {
                send(sender, "history.usage", "&7Usage: &f/acm history <player> [page]");
                return;
            }
            String name = args[0];
            int page = 1;
            if (args.length > 1 && isInt(args[1])) page = Math.max(1, Integer.parseInt(args[1]));
            int finalPage = page;
            String key = resolvePlayerKey(name);
            plugin.getStorage().loadViolations(key, PAGE_SIZE, (page - 1) * PAGE_SIZE)
                    .thenAccept(rows -> plugin.getLib().getSchedulerModule().runSync(() -> {
                        if (rows.isEmpty()) {
                            send(sender, "history.empty", "&7No violation history for &f{player}&7.",
                                    "player", name);
                            return;
                        }
                        sendRaw(sender, "history.header", "&b=== Violation History: &f{player} &7(page {page})",
                                "player", name, "page", finalPage);
                        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                        for (Storage.ViolationRow row : rows) {
                            sendRaw(sender, "history.line",
                                    "&7{time} &8| &f{command} &8| &7world={world} source={source}",
                                    "time", fmt.format(new Date(row.createdAt)),
                                    "command", row.command,
                                    "world", row.world == null ? "-" : row.world,
                                    "source", row.source == null ? "-" : row.source);
                        }
                    }))
                    .exceptionally(ex -> {
                        plugin.getLogger().log(java.util.logging.Level.WARNING,
                                "Failed to load history for " + name, ex);
                        plugin.getLib().getSchedulerModule().runSync(() ->
                                send(sender, "errors.internal",
                                        "&cAn internal error occurred. Check the console."));
                        return null;
                    });
        }
    }

    /* ------------------------------------------------------------------ */
    /* violations                                                         */
    /* ------------------------------------------------------------------ */

    private final class ViolationsSub extends Base {
        @Override public @NotNull String name() { return "violations"; }
        @Override public String description() { return "Show or reset violation counts"; }
        @Override public String usage() { return "/acm violations [reset] <player>"; }

        @Override
        public @NotNull List<String> tabComplete(@NotNull CommandSender sender, String[] args) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            if (args.length == 1) {
                List<String> options = new ArrayList<>(names);
                options.add("reset");
                return filter(options, args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
                return filter(names, args[1]);
            }
            return List.of();
        }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length < 1) {
                send(sender, "violations.usage", "&7Usage: &f/acm violations [reset] <player>");
                return;
            }
            boolean reset = args[0].equalsIgnoreCase("reset");
            int nameIndex = reset ? 1 : 0;
            if (args.length <= nameIndex) {
                send(sender, "violations.usage", "&7Usage: &f/acm violations [reset] <player>");
                return;
            }
            String name = args[nameIndex];
            String key = resolvePlayerKey(name);

            if (reset) {
                plugin.getPunishmentTracker().resetPlayer(key);
                send(sender, "violations.reset", "&aViolation count reset for &f{player}&a.",
                        "player", name);
                return;
            }

            int memory = plugin.getPunishmentTracker().getMemoryCount(key);
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) {
                memory = Math.max(memory, plugin.getPunishmentTracker().getMemoryCount(online.getUniqueId().toString()));
                key = online.getUniqueId().toString();
            }
            final String finalKey = key;
            final int memoryCount = memory;
            plugin.getStorage().getViolationCount(finalKey).thenAccept(dbCount ->
                    plugin.getLib().getSchedulerModule().runSync(() -> {
                        sendRaw(sender, "violations.header", "&b=== Violations: &f{player}",
                                "player", name);
                        sendRaw(sender, "violations.line", "&7  {key}: &f{value}",
                                "key", plugin.getLang().message("violations.key.memory", "in-memory"),
                                "value", memoryCount);
                        sendRaw(sender, "violations.line", "&7  {key}: &f{value}",
                                "key", plugin.getLang().message("violations.key.database", "database"),
                                "value", dbCount);
                    })).exceptionally(ex -> {
                        plugin.getLogger().log(java.util.logging.Level.WARNING,
                                "Failed to read violations for " + name, ex);
                        plugin.getLib().getSchedulerModule().runSync(() ->
                                send(sender, "errors.internal",
                                        "&cAn internal error occurred. Check the console."));
                        return null;
                    });
        }
    }

    /* ------------------------------------------------------------------ */
    /* template                                                           */
    /* ------------------------------------------------------------------ */

    private final class TemplateSub extends Base {
        @Override public @NotNull String name() { return "template"; }
        @Override public String description() { return "Manage alert templates"; }
        @Override public String usage() { return "/acm template [list|set|preview]"; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                sendRaw(sender, "template.list.header", "&b=== Alert Templates &7(active: {active}&7)",
                        "active", plugin.getAlertManager().getTemplate());
                for (String name : plugin.getAlertManager().getTemplates().keySet()) {
                    sendRaw(sender, "template.list.line", "&7- &f{name}", "name", name);
                }
                return;
            }
            if (args.length < 2) {
                send(sender, "template.usage", "&7Usage: &f/acm template <list|set|preview> <name>");
                return;
            }
            String action = args[0].toLowerCase(Locale.ROOT);
            String name = args[1].toLowerCase(Locale.ROOT);
            switch (action) {
                case "set" -> {
                    if (!plugin.getAlertManager().getTemplates().containsKey(name)) {
                        send(sender, "template.notfound", "&cUnknown template: &7{name}", "name", name);
                        return;
                    }
                    plugin.getAlertManager().setTemplate(name);
                    send(sender, "template.set", "&aAlert template set to &f{name}&a.", "name", name);
                }
                case "preview" -> {
                    String preview = plugin.getAlertManager().previewTemplate(name);
                    if (preview == null) {
                        send(sender, "template.notfound", "&cUnknown template: &7{name}", "name", name);
                        return;
                    }
                    sendRaw(sender, "template.preview.header", "&b=== Preview: &f{name}", "name", name);
                    for (String line : preview.split("\n")) {
                        sender.sendMessage(render(line, false));
                    }
                }
                default -> send(sender, "template.usage",
                        "&7Usage: &f/acm template <list|set|preview> <name>");
            }
        }

        @Override
        public @NotNull List<String> tabComplete(CommandSender sender, String[] args) {
            if (args.length == 1) {
                return filter(List.of("list", "set", "preview"), args[0]);
            }
            if (args.length == 2) {
                return filter(new ArrayList<>(plugin.getAlertManager().getTemplates().keySet()), args[1]);
            }
            return List.of();
        }
    }

    /* ------------------------------------------------------------------ */
    /* language                                                           */
    /* ------------------------------------------------------------------ */

    private final class LanguageSub extends Base {
        @Override public @NotNull String name() { return "language"; }
        @Override public String description() { return "Show or change the language"; }
        @Override public String usage() { return "/acm language [list|reload|set <code|number>]"; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                showLanguages(sender, label);
                return;
            }
            if (args[0].equalsIgnoreCase("reload")) {
                plugin.getLang().reload();
                plugin.getAlertManager().reloadConfig();
                send(sender, "language.reload", "&aLanguage files reloaded.");
                return;
            }
            // Supports /acm language set <code|number> and /acm language <code|number>.
            String requested = args[0].equalsIgnoreCase("set")
                    ? (args.length > 1 ? args[1] : null)
                    : args[0];
            if (requested == null) {
                send(sender, "language.usage",
                        "&7Usage: &f/{label} language [list|reload|set <code|number>]",
                        "label", label);
                return;
            }
            String normalized = resolveLanguage(requested);
            if (normalized == null) {
                send(sender, "language.notfound", "&cUnknown language: &7{code}",
                        "code", requested);
                send(sender, "language.available", "&7Available languages: &f{codes}",
                        "codes", availableLanguages());
                return;
            }
            // Persist first, then reload the files and dependent caches. The
            // confirmation below is looked up from the newly active language.
            plugin.getLang().setActiveCode(normalized);
            plugin.getLang().reload();
            plugin.getAlertManager().reloadConfig();
            send(sender, "language.set", "&aLanguage set to &f{code} &7({name})&a.",
                    "code", normalized, "name", plugin.getLang().getDisplayName(normalized));
        }

        private void showLanguages(CommandSender sender, String label) {
            List<String> codes = plugin.getLang().getCodes();
            String active = plugin.getLang().getActiveCode();
            sendRaw(sender, "language.header", "&b=== Language &7(active: {active}&7)",
                    "active", active + " (" + plugin.getLang().getDisplayName(active) + ")");
            for (int i = 0; i < codes.size(); i++) {
                String code = codes.get(i);
                String key = code.equals(active) ? "language.line-active" : "language.line";
                String def = code.equals(active)
                        ? "&7{index}. &f{code} &7({name}) &a<- active"
                        : "&7{index}. &f{code} &7({name})";
                sendRaw(sender, key, def,
                        "index", i + 1,
                        "code", code,
                        "name", plugin.getLang().getDisplayName(code));
            }
            send(sender, "language.hint",
                    "&7Use &f/{label} language set <code|number>&7 to change the active language.",
                    "label", label);
        }

        /** Resolve a 1-based list number or a case-insensitive language code. */
        private @Nullable String resolveLanguage(String requested) {
            if (isInt(requested)) {
                int index = Integer.parseInt(requested);
                List<String> codes = plugin.getLang().getCodes();
                if (index >= 1 && index <= codes.size()) return codes.get(index - 1);
                return null;
            }
            String normalized = plugin.getLang().normalizeCode(requested);
            return plugin.getLang().hasCode(normalized) ? normalized : null;
        }

        private String availableLanguages() {
            List<String> codes = plugin.getLang().getCodes();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < codes.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(i + 1).append(". ").append(codes.get(i));
            }
            return out.toString();
        }

        @Override
        public @NotNull List<String> tabComplete(CommandSender sender, String[] args) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of("list", "reload", "set"));
                options.addAll(plugin.getLang().getCodes());
                return filter(options, args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                return filter(new ArrayList<>(plugin.getLang().getCodes()), args[1]);
            }
            return List.of();
        }
    }

    /* ------------------------------------------------------------------ */
    /* export / import                                                    */
    /* ------------------------------------------------------------------ */

    private final class ExportSub extends Base {
        @Override public @NotNull String name() { return "export"; }
        @Override public String description() { return "Export blacklist to YAML"; }
        @Override public String usage() { return "/acm export [file]"; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            String fileName = args.length > 0 ? args[0] : "blacklist-export.yml";
            if (!fileName.endsWith(".yml")) fileName += ".yml";
            File resolved = safeDataFile(fileName);
            if (resolved == null) {
                send(sender, "export.failed", "&cExport failed: {error}", "error", "unsafe path");
                return;
            }
            final String finalName = fileName;
            plugin.getLib().getSchedulerModule().runAsync(() -> {
                File file = resolved;
                YamlConfiguration out = new YamlConfiguration();
                List<Map<String, Object>> list = new ArrayList<>();
                for (BlacklistManager.Entry e : plugin.getBlacklist().getSortedEntries()) {
                    Map<String, Object> map = new java.util.LinkedHashMap<>();
                    map.put("command", e.command);
                    map.put("mode", e.mode.name());
                    map.put("source", e.sourceLabel());
                    map.put("action", e.action.name());
                    if (e.pluginName != null) map.put("plugin", e.pluginName);
                    if (e.permission != null) map.put("permission", e.permission);
                    if (e.reason != null) map.put("reason", e.reason);
                    map.put("enabled", e.enabled);
                    list.add(map);
                }
                out.set("entries", list);
                try {
                    File parent = file.getParentFile();
                    if (parent != null) parent.mkdirs();
                    out.save(file);
                    plugin.getLib().getSchedulerModule().runSync(() ->
                            send(sender, "export.done", "&aExported &f{count} &aentries to &f{file}&a.",
                                    "count", list.size(), "file", finalName));
                } catch (IOException ex) {
                    plugin.getLogger().warning("Export failed: " + ex.getMessage());
                    plugin.getLib().getSchedulerModule().runSync(() ->
                            send(sender, "export.failed", "&cExport failed: {error}",
                                    "error", ex.getMessage()));
                }
            });
        }
    }

    private final class ImportSub extends Base {
        @Override public @NotNull String name() { return "import"; }
        @Override public String description() { return "Import blacklist from YAML"; }
        @Override public String usage() { return "/acm import <file>"; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length < 1) {
                send(sender, "import.usage", "&7Usage: &f/acm import <file>");
                return;
            }
            String fileName = args[0].endsWith(".yml") ? args[0] : args[0] + ".yml";
            File file = safeDataFile(fileName);
            if (file == null) {
                send(sender, "import.notfound", "&cFile not found: &f{file}", "file", fileName);
                return;
            }
            if (!file.exists() || !file.isFile()) {
                send(sender, "import.notfound", "&cFile not found: &f{file}", "file", fileName);
                return;
            }
            if (file.length() > 1024 * 1024) {
                send(sender, "import.failed", "&cImport failed: {error}", "error", "file too large");
                return;
            }
            plugin.getLib().getSchedulerModule().runAsync(() -> {
                YamlConfiguration in;
                try {
                    String raw = java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                    if (unsafeYaml(raw)) {
                        throw new IllegalArgumentException("unsafe YAML structure");
                    }
                    in = new YamlConfiguration();
                    in.loadFromString(raw);
                } catch (Exception ex) {
                    plugin.getLib().getSchedulerModule().runSync(() ->
                            send(sender, "import.failed", "&cImport failed: {error}",
                                    "error", ex.getMessage() == null ? "invalid YAML" : ex.getMessage()));
                    return;
                }
                List<Map<?, ?>> list = in.getMapList("entries");
                if (list.size() > 5000) list = list.subList(0, 5000);
                int added = 0;
                int skipped = 0;
                for (Map<?, ?> map : list) {
                    Object cmdObj = map.get("command");
                    if (cmdObj == null) continue;
                    String command = normalizeInput(String.valueOf(cmdObj));
                    if (plugin.getBlacklist().contains(command)) {
                        skipped++;
                        continue;
                    }
                    BlacklistManager.Entry e = new BlacklistManager.Entry();
                    e.command = command;
                    e.normalized = plugin.getBlacklist().canonicalize(command);
                    try {
                        Object modeObj = map.get("mode");
                        e.mode = BlacklistManager.MatchMode.valueOf(
                                String.valueOf(modeObj == null ? "EXACT" : modeObj).toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ex) {
                        e.mode = BlacklistManager.MatchMode.EXACT;
                    }
                    if (e.mode == BlacklistManager.MatchMode.REGEX && !plugin.getBlacklist().isSafeRegex(command)) {
                        skipped++;
                        continue;
                    }
                    e.source = BlacklistManager.Source.IMPORT;
                    e.action = BlacklistManager.Action.parse(map.get("action") == null
                            ? "block" : String.valueOf(map.get("action")));
                    e.pluginName = map.get("plugin") != null ? String.valueOf(map.get("plugin")) : null;
                    e.permission = map.get("permission") != null ? String.valueOf(map.get("permission")) : null;
                    e.reason = map.get("reason") != null ? String.valueOf(map.get("reason"))
                            : plugin.getLang().message("import.default-reason", "Imported");
                    e.addedBy = sender.getName();
                    e.addedAt = System.currentTimeMillis();
                    plugin.getBlacklist().addEntry(e);
                    added++;
                }
                final int addedCount = added;
                final int skippedCount = skipped;
                plugin.getLib().getSchedulerModule().runSync(() ->
                        send(sender, "import.done",
                                "&aImported &f{added} &aentries (&f{skipped} &askipped as duplicates).",
                                "added", addedCount, "skipped", skippedCount));
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                            */
    /* ------------------------------------------------------------------ */

    private String joinArgs(String[] args, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, Math.min(from, args.length), args.length)).trim();
    }

    private String normalizeInput(String input) {
        return plugin.getBlacklist().canonicalize(input);
    }

    /**
     * Resolve an import/export file strictly inside this plugin's data folder.
     * Rejects absolute paths, traversal, NUL bytes, symlinks, and unusual names.
     */
    private @Nullable File safeDataFile(@NotNull String name) {
        if (name.isBlank() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0) return null;
        try {
            java.nio.file.Path base = plugin.getDataFolder().getCanonicalFile().toPath();
            java.nio.file.Path target = base.resolve(name).normalize();
            if (!target.startsWith(base) || target.equals(base)) return null;
            File file = target.toFile();
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) return null;
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    /** Cheap YAML-bomb / unsafe-tag guard before SnakeYAML sees the input. */
    private boolean unsafeYaml(@NotNull String raw) {
        if (raw.length() > 1024 * 1024) return true;
        if (raw.contains("!!") || raw.contains("!java") || raw.contains("!javax")) return true;
        int aliases = 0;
        int depth = 0;
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("&") || t.startsWith("*")) aliases++;
            if (aliases > 256) return true;
            depth += t.startsWith("-") ? 1 : 0;
            if (depth > 5000) return true;
        }
        return false;
    }

    private String displayId(int id) {
        return id < 0 ? plugin.getLang().message("blacklist.pending", "pending") : String.valueOf(id);
    }

    private String resolvePlayerKey(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId().toString();
        // Never call getOfflinePlayer(String): it can block on a web lookup.
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) return cached.getUniqueId().toString();
        // Qualify on player_name in the DAO so history still works offline.
        return name;
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(p)) out.add(option);
        }
        return out;
    }
}
