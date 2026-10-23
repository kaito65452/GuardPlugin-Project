package id.saryu.antichatmanager.command;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.antichatmanager.audit.PermissionAudit;
import id.saryu.antichatmanager.blacklist.BlacklistManager;
import id.saryu.antichatmanager.conflict.ConflictDetector;
import id.saryu.antichatmanager.security.SecurityAudit;
import id.saryu.libcore.command.CommandFramework;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

final class PluginsSub implements CommandFramework.SubCommand {
    private final AntiChatManager plugin;
    PluginsSub(AntiChatManager plugin) { this.plugin = plugin; }
    @Override public @NotNull String name() { return "plugins"; }
    @Override public @NotNull String permission() { return "saryuantichatmanager.admin"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 0; }
    @Override public String description() { return "Show plugin identity results"; }
    @Override public String usage() { return "/acm plugins"; }
    @Override public @NotNull List<String> tabComplete(CommandSender sender, String[] args) { return List.of(); }
    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        if (plugin.getPluginIdentityService() == null) {
            send(sender, "messages.internal-error", "%prefix%&cAn internal error occurred. Check the console.");
            return;
        }
        var all = plugin.getPluginIdentityService().getAll();
        if (all.isEmpty()) {
            send(sender, "plugins.empty", "&7No plugin identity data yet. Run &f/acm scan identify&7.");
            return;
        }
        send(sender, "plugins.header", "&b=== Plugin Identity ===");
        for (var e : all.entrySet()) {
            var id = e.getValue();
            String status = id.verified ? "&aVERIFIED" : "&eUNVERIFIED";
            String project = id.verified && id.projectTitle != null ? "&7[" + id.projectTitle + "]" : "";
            String msg = plugin.getLang().message("plugins.line", "&7{plugin} &8- &f{status} &7(conf={confidence}%) {project}");
            msg = msg.replace("{plugin}", e.getKey()).replace("{status}", status).replace("{confidence}", String.valueOf(id.confidence)).replace("{project}", project);
            sender.sendMessage(plugin.getMsg().parse(msg));
        }
    }
    private void send(CommandSender s, String key, String def) {
        String m = plugin.getLang().message(key, def);
        s.sendMessage(plugin.getMsg().parse(m));
    }
}

final class ConflictsSub implements CommandFramework.SubCommand {
    private final AntiChatManager plugin;
    ConflictsSub(AntiChatManager plugin) { this.plugin = plugin; }
    @Override public @NotNull String name() { return "conflicts"; }
    @Override public @NotNull String permission() { return "saryuantichatmanager.admin"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 0; }
    @Override public String description() { return "LuckPerms conflict report"; }
    @Override public String usage() { return "/acm conflicts [page|ignore <cmd> [group]|allow <cmd> <group>]"; }
    @Override public @NotNull List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return filter(List.of("ignore", "allow"), args[0]);
        if (args.length == 2) return filter(new ArrayList<>(plugin.getBlacklist().getAllEntries().stream().map(e -> e.command).toList()), args[1]);
        return List.of();
    }
    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        if (plugin.getConflictDetector() == null || !plugin.getConflictDetector().isEnabled()) {
            send(sender, "conflicts.empty", "&7No LuckPerms conflicts found.");
            return;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("allow")) {
            plugin.getConflictDetector().whitelistGroup(args[1], args[2]);
            send(sender, "conflicts.resolved", "&aConflict marked resolved.");
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("ignore")) {
            String group = args.length >= 3 ? args[2] : null;
            plugin.getConflictDetector().ignore(args[1], group);
            send(sender, "conflicts.ignored", "&aConflict ignored.");
            return;
        }
        var open = plugin.getConflictDetector().getOpen();
        if (open.isEmpty()) {
            send(sender, "conflicts.empty", "&7No LuckPerms conflicts found.");
            return;
        }
        int page = 1;
        if (args.length >= 1 && args[0].matches("\\d+")) {
            try { page = Math.max(1, Integer.parseInt(args[0])); } catch (Exception ignored) {}
        }
        int perPage = 8;
        int total = (open.size() + perPage - 1) / perPage;
        int start = (page - 1) * perPage;
        String header = plugin.getLang().message("conflicts.header", "&b=== LuckPerms Conflicts (page {page}/{total}) ===")
                .replace("{page}", String.valueOf(page)).replace("{total}", String.valueOf(total));
        sender.sendMessage(plugin.getMsg().parse(header));
        for (int i = start; i < Math.min(start + perPage, open.size()); i++) {
            var c = open.get(i);
            String msg = plugin.getLang().message("conflicts.line", "&7[{severity}] &f{command} &7group={group} perm={permission} source={source}");
            msg = msg.replace("{severity}", c.severity.name()).replace("{command}", c.command)
                    .replace("{group}", c.groupName).replace("{permission}", c.permission == null ? "-" : c.permission)
                    .replace("{source}", c.source == null ? "-" : c.source);
            sender.sendMessage(plugin.getMsg().parse(msg));
        }
    }
    private void send(CommandSender s, String key, String def) {
        String m = plugin.getLang().message(key, def);
        s.sendMessage(plugin.getMsg().parse(m));
    }
    private List<String> filter(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(java.util.Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option != null && option.toLowerCase(java.util.Locale.ROOT).startsWith(p)) out.add(option);
        }
        return out;
    }
}

final class AuditSub implements CommandFramework.SubCommand {
    private final AntiChatManager plugin;
    AuditSub(AntiChatManager plugin) { this.plugin = plugin; }
    @Override public @NotNull String name() { return "audit"; }
    @Override public @NotNull String permission() { return "saryuantichatmanager.admin"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 0; }
    @Override public String description() { return "Permission audit report"; }
    @Override public String usage() { return "/acm audit [page|info <id>|ack <id>|ignore <group|user|node> <name>]"; }
    @Override public @NotNull List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return filter(List.of("info", "ack", "ignore"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("ignore")) {
            return filter(List.of("group", "user", "node"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("ignore")) {
            String type = args[1].toLowerCase(java.util.Locale.ROOT);
            if (type.equals("group")) {
                return filter(new ArrayList<>(plugin.getLib().getPermissionModule().getLoadedGroupNames()), args[2]);
            }
            if (type.equals("node")) {
                return filter(new ArrayList<>(plugin.getBlacklist().getAllEntries().stream()
                        .map(e -> e.permission).filter(java.util.Objects::nonNull).distinct().toList()), args[2]);
            }
        }
        return List.of();
    }
    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        if (plugin.getPermissionAudit() == null || !plugin.getPermissionAudit().isEnabled()) {
            send(sender, "audit.empty", "&7No open audit findings.");
            return;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("ignore")) {
            String type = args[1].toLowerCase(java.util.Locale.ROOT);
            if (!type.equals("group") && !type.equals("user") && !type.equals("node")
                    && !type.equals("permission") && !type.equals("perm")) {
                send(sender, "audit.usage", "&7Usage: &f/acm audit ignore <group|user|node> <name>");
                return;
            }
            plugin.getPermissionAudit().ignore(args[2], type);
            send(sender, "audit.ignore", "&aSubject/node added to audit ignore list.");
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("ack")) {
            try {
                long id = Long.parseLong(args[1]);
                plugin.getPermissionAudit().acknowledge(id);
                send(sender, "audit.ack", "&aFinding acknowledged.");
            } catch (Exception ignored) { send(sender, "audit.usage", "&7Usage: &f/acm audit [info|ack|ignore]"); }
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("info")) {
            try {
                long id = Long.parseLong(args[1]);
                var f = plugin.getPermissionAudit().get(id);
                if (f == null) { send(sender, "audit.empty", "&7No open audit findings."); return; }
                String title = plugin.getLang().message("audit.info.title", "&b=== Audit Finding #{id} ===").replace("{id}", String.valueOf(id));
                sender.sendMessage(plugin.getMsg().parse(title));
                sender.sendMessage(plugin.getMsg().parse(plugin.getLang().message("audit.info.type",
                        "&7Type: &f{type} &7Subject: &f{subject}")
                        .replace("{type}", f.subjectType).replace("{subject}", f.subjectName)));
                sender.sendMessage(plugin.getMsg().parse(plugin.getLang().message("audit.info.node",
                        "&7Node: &f{node} &7Risk: &f{risk}")
                        .replace("{node}", f.node).replace("{risk}", f.risk.name())));
                sender.sendMessage(plugin.getMsg().parse(plugin.getLang().message("audit.info.origin",
                        "&7Origin: &f{origin} &7Status: &f{status}")
                        .replace("{origin}", f.origin == null ? "-" : f.origin)
                        .replace("{status}", f.status)));
            } catch (Exception ignored) { send(sender, "audit.usage", "&7Usage: &f/acm audit [info|ack|ignore]"); }
            return;
        }
        var open = plugin.getPermissionAudit().getOpen();
        if (open.isEmpty()) {
            send(sender, "audit.empty", "&7No open audit findings.");
            return;
        }
        int page = 1;
        if (args.length >= 1 && args[0].matches("\\d+")) {
            try { page = Math.max(1, Integer.parseInt(args[0])); } catch (Exception ignored) {}
        }
        int perPage = 8;
        int total = (open.size() + perPage - 1) / perPage;
        int start = (page - 1) * perPage;
        String header = plugin.getLang().message("audit.header", "&b=== Permission Audit (page {page}/{total}) ===")
                .replace("{page}", String.valueOf(page)).replace("{total}", String.valueOf(total));
        sender.sendMessage(plugin.getMsg().parse(header));
        for (int i = start; i < Math.min(start + perPage, open.size()); i++) {
            var f = open.get(i);
            String msg = plugin.getLang().message("audit.line", "&7[{severity}] &f{subject} ({type}) &7has &f{node} &8origin={origin}");
            msg = msg.replace("{severity}", f.risk.name()).replace("{subject}", f.subjectName)
                    .replace("{type}", f.subjectType).replace("{node}", f.node)
                    .replace("{origin}", f.origin == null ? "-" : f.origin);
            sender.sendMessage(plugin.getMsg().parse(msg));
        }
    }
    private void send(CommandSender s, String key, String def) {
        String m = plugin.getLang().message(key, def);
        s.sendMessage(plugin.getMsg().parse(m));
    }
    private List<String> filter(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(java.util.Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option != null && option.toLowerCase(java.util.Locale.ROOT).startsWith(p)) out.add(option);
        }
        return out;
    }
}

final class SecuritySub implements CommandFramework.SubCommand {
    private final AntiChatManager plugin;
    SecuritySub(AntiChatManager plugin) { this.plugin = plugin; }
    @Override public @NotNull String name() { return "security"; }
    @Override public @NotNull String permission() { return "saryuantichatmanager.admin"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 0; }
    @Override public String description() { return "Run security audit"; }
    @Override public String usage() { return "/acm security"; }
    @Override public @NotNull List<String> tabComplete(CommandSender sender, String[] args) { return List.of(); }
    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        if (plugin.getSecurityAudit() == null) {
            send(sender, "messages.internal-error", "%prefix%&cAn internal error occurred. Check the console.");
            return;
        }
        send(sender, "security.header", "&b=== Security Audit ===");
        for (SecurityAudit.Finding f : plugin.getSecurityAudit().audit()) {
            String key = switch (f.level) {
                case "CRITICAL" -> "security.critical";
                case "WARNING" -> "security.warning";
                default -> "security.ok";
            };
            String msg = plugin.getLang().message(key, "[{level}] {msg}");
            msg = msg.replace("{level}", f.level).replace("{msg}", f.message);
            sender.sendMessage(plugin.getMsg().parse(msg));
        }
        send(sender, "security.done", "&7Run again with &f/acm security&7.");
    }
    private void send(CommandSender s, String key, String def) {
        String m = plugin.getLang().message(key, def);
        s.sendMessage(plugin.getMsg().parse(m));
    }
}
