package id.saryu.antichatmanager.command;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.antichatmanager.blacklist.BlacklistManager;
import id.saryu.antichatmanager.integration.PluginIntegrationService;
import id.saryu.libcore.command.CommandFramework;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class IntegrationSub implements CommandFramework.SubCommand {
    private final AntiChatManager plugin;

    IntegrationSub(AntiChatManager plugin) { this.plugin = plugin; }

    @Override public @NotNull String name() { return "integration"; }
    @Override public @NotNull String permission() { return "saryuantichatmanager.integration.admin"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 0; }
    @Override public String description() { return "WorldEdit/FAWE/WorldGuard integration"; }
    @Override public String usage() { return "/acm integration <status|scan|reload|apply [worldedit|fawe|worldguard|all]>"; }

    @Override
    public @NotNull List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return filter(List.of("status", "scan", "reload", "apply"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("apply")) {
            return filter(List.of("worldedit", "fawe", "worldguard", "all"), args[1]);
        }
        return List.of();
    }

    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        PluginIntegrationService service = plugin.getPluginIntegrationService();
        if (service == null) {
            send(sender, "messages.internal-error", "%prefix%&cAn internal error occurred. Check the console.");
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            status(sender, service);
            return;
        }
        if (args[0].equalsIgnoreCase("scan")) {
            send(sender, "integration.scan.started", "&7Scanning sensitive plugins asynchronously...");
            service.scanAsync(() -> send(sender, "integration.scan.done",
                    "&aIntegration scan finished: &f{mappings} &amapping(s), &f{recommendations} &arecommendation(s).",
                    "mappings", String.valueOf(service.getMappings().size()),
                    "recommendations", String.valueOf(service.getRecommendations().size())));
            return;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            service.reloadConfig();
            plugin.getBlacklist().reloadConfigRules();
            service.scanAsync(null);
            send(sender, "integration.reload", "&aIntegration config reloaded; rescan started.");
            return;
        }
        if (args[0].equalsIgnoreCase("apply")) {
            if (args.length < 2) {
                send(sender, "integration.apply.usage",
                        "&7Usage: &f/acm integration apply <worldedit|fawe|worldguard|all>");
                listRecommendations(sender, service);
                return;
            }
            try {
                PluginIntegrationService.ApplyResult result = service.applyRecommendations(args[1]);
                if (result.selected == 0) {
                    send(sender, "integration.apply.none",
                            "&7No recommendations matched &f{target}&7. Run &f/acm integration scan&7 first.",
                            "target", args[1]);
                    return;
                }
                send(sender, "integration.apply.done",
                        "&aApplied &f{added} &arule(s), skipped &f{skipped} &aexisting rule(s) for &f{target}&a.",
                        "added", String.valueOf(result.added), "skipped", String.valueOf(result.skipped),
                        "target", args[1]);
                for (String command : result.addedCommands) {
                    sendRaw(sender, "integration.apply.line", "&7  + &f{command}", "command", command);
                }
            } catch (IllegalArgumentException ex) {
                send(sender, "integration.apply.usage",
                        "&7Usage: &f/acm integration apply <worldedit|fawe|worldguard|all>");
            }
            return;
        }
        send(sender, "command.usage", "%prefix%&7Usage: &f{usage}", "usage", usage());
    }

    private void status(CommandSender sender, PluginIntegrationService service) {
        sendRaw(sender, "integration.header", "&b=== Plugin Integration ===");
        sendRaw(sender, "integration.status", "&7Enabled: &f{enabled} &7compat-mode: &f{compat} &7scanning: &f{scanning}",
                "enabled", String.valueOf(service.isIntegrationEnabled()),
                "compat", String.valueOf(service.isCompatMode()),
                "scanning", String.valueOf(service.isScanning()));
        sendRaw(sender, "integration.manual-list", "&7plugin-manual-scan: &f{plugins}",
                "plugins", service.getManualPlugins().isEmpty() ? "-" : String.join(", ", service.getManualPlugins()));
        if (service.getMappings().isEmpty()) {
            sendRaw(sender, "integration.empty", "&7No plugin mappings yet. Run &f/acm integration scan&7.");
            return;
        }
        for (PluginIntegrationService.Mapping mapping : service.getMappings()) {
            sendRaw(sender, "integration.mapping.line",
                    "&7- &f{plugin} &8v{version} &7installed={installed} commands={commands} permissions={permissions} listeners={listeners}",
                    "plugin", mapping.pluginName, "version", mapping.version,
                    "installed", String.valueOf(mapping.installed),
                    "commands", String.valueOf(mapping.commands.size()),
                    "permissions", String.valueOf(mapping.permissions.size()),
                    "listeners", String.valueOf(mapping.listeners.size()));
            for (String warning : mapping.warnings) {
                sendRaw(sender, "integration.mapping.warning", "&8    ! &7{warning}", "warning", warning);
            }
        }
        listRecommendations(sender, service);
        if (!service.getConfigWarnings().isEmpty()) {
            sendRaw(sender, "integration.config.header", "&e=== Read-only plugin config warnings ===");
            for (String warning : service.getConfigWarnings()) {
                sendRaw(sender, "integration.config.line", "&7  ! &f{warning}", "warning", warning);
            }
        }
    }

    private void listRecommendations(CommandSender sender, PluginIntegrationService service) {
        if (service.getRecommendations().isEmpty()) return;
        sendRaw(sender, "integration.recommendations.header", "&b=== Opt-in Recommendations &7({count})",
                "count", String.valueOf(service.getRecommendations().size()));
        for (PluginIntegrationService.Recommendation rec : service.getRecommendations()) {
            sendRaw(sender, "integration.recommendations.line",
                    "&7- &f{command} &8[{plugin}] &7verified={verified} &8{reason}",
                    "command", rec.command, "plugin", rec.plugin,
                    "verified", String.valueOf(rec.verifiedInCommandMap), "reason", rec.reason);
        }
        sendRaw(sender, "integration.recommendations.hint",
                "&7Use &f/acm integration apply <worldedit|fawe|worldguard|all>&7 to confirm.");
    }

    private void send(CommandSender sender, String key, String def, Object... vars) {
        sender.sendMessage(plugin.getMsg().parse(render(fill(plugin.getLang().message(key, def), vars), true)));
    }

    private void sendRaw(CommandSender sender, String key, String def, Object... vars) {
        sender.sendMessage(plugin.getMsg().parse(render(fill(plugin.getLang().message(key, def), vars), false)));
    }

    private String render(String text, boolean addPrefix) {
        String out = text == null ? "" : text;
        String prefix = plugin.getConfigModule().getString("prefix", "");
        if (prefix == null || prefix.isBlank()) prefix = plugin.getLang().get("prefix", "&7[&bACM&7]");
        return out.contains("%prefix%") ? out.replace("%prefix%", prefix)
                : (addPrefix ? prefix + " " + out : out);
    }

    private String fill(String text, Object... vars) {
        String out = text == null ? "" : text;
        for (int i = 0; i + 1 < vars.length; i += 2) {
            out = out.replace("{" + vars[i] + "}", plugin.getMsg().escape(String.valueOf(vars[i + 1])));
        }
        return out;
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) if (option.startsWith(p)) out.add(option);
        return out;
    }
}

final class SubcmdSub implements CommandFramework.SubCommand {
    private final AntiChatManager plugin;

    SubcmdSub(AntiChatManager plugin) { this.plugin = plugin; }

    @Override public @NotNull String name() { return "subcmd"; }
    @Override public @NotNull String permission() { return "saryuantichatmanager.subcmd.admin"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 0; }
    @Override public String description() { return "Manage subcommand blacklist rules"; }
    @Override public String usage() {
        return "/acm subcmd add [block|alert-only|allow] <command...> | remove <command...> | list";
    }

    @Override
    public @NotNull List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return filter(List.of("add", "remove", "list"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            return filter(List.of("block", "alert-only", "allow"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            List<String> commands = new ArrayList<>();
            for (BlacklistManager.Entry entry : plugin.getBlacklist().getAllEntries()) {
                if (entry.command.indexOf(' ') >= 0) commands.add(entry.command);
            }
            return filter(commands, args[1]);
        }
        return List.of();
    }

    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            send(sender, "subcmd.usage", "%prefix%&7Usage: &f" + usage());
            return;
        }
        if (args[0].equalsIgnoreCase("list")) {
            list(sender);
            return;
        }
        if (args[0].equalsIgnoreCase("remove")) {
            if (args.length < 2) {
                send(sender, "subcmd.usage", "%prefix%&7Usage: &f" + usage());
                return;
            }
            String command = join(args, 1);
            if (!valid(command)) {
                send(sender, "subcmd.invalid", "&cInvalid command rule.");
                return;
            }
            boolean removed = plugin.getBlacklist().removeConfigRulePublic(command);
            if (!removed) {
                send(sender, "subcmd.notfound", "&cNo command rule found for &7{command}&c.",
                        "command", command);
                return;
            }
            send(sender, "subcmd.removed", "&aRemoved command rule &f{command}&a.", "command", command);
            return;
        }
        if (!args[0].equalsIgnoreCase("add")) {
            send(sender, "subcmd.usage", "%prefix%&7Usage: &f" + usage());
            return;
        }

        int start = 1;
        String requestedMode = "block";
        if (args.length >= 2 && isMode(args[1])) {
            requestedMode = args[1].toLowerCase(Locale.ROOT);
            start = 2;
        }
        if (args.length <= start) {
            send(sender, "subcmd.usage", "%prefix%&7Usage: &f" + usage());
            return;
        }
        String command = join(args, start);
        if (!valid(command)) {
            send(sender, "subcmd.invalid", "&cInvalid command rule: max 256 characters, no dots/control chars.");
            return;
        }
        String normalized = plugin.getBlacklist().canonicalize(command);
        ConfigurationSection section = plugin.getConfigModule().get().getConfigurationSection("commands");
        if (section == null) section = plugin.getConfigModule().get().createSection("commands");
        if (section.isConfigurationSection(normalized)) {
            send(sender, "subcmd.exists", "&cRule already exists for &7{command}&c. Remove it first.",
                    "command", normalized);
            return;
        }
        ConfigurationSection rule = section.createSection(normalized);
        rule.set("mode", BlacklistManager.Action.parse(requestedMode).name().toLowerCase(Locale.ROOT).replace('_', '-'));
        plugin.getConfigModule().save();
        plugin.getBlacklist().reloadConfigRules();
        send(sender, "subcmd.added", "&aAdded &f{command} &awith mode &f{mode}&a.",
                "command", normalized, "mode", requestedMode);
    }

    private void list(CommandSender sender) {
        List<BlacklistManager.Entry> rules = new ArrayList<>();
        for (BlacklistManager.Entry entry : plugin.getBlacklist().getAllEntries()) {
            if (entry.source == BlacklistManager.Source.CONFIG) rules.add(entry);
        }
        if (rules.isEmpty()) {
            send(sender, "subcmd.list.empty", "&7No subcommand rules configured.");
            return;
        }
        rules.sort(java.util.Comparator.comparing(e -> e.command.toLowerCase(Locale.ROOT)));
        sendRaw(sender, "subcmd.list.header", "&b=== Subcommand Rules &7({count})",
                "count", String.valueOf(rules.size()));
        for (BlacklistManager.Entry entry : rules) {
            sendRaw(sender, "subcmd.list.line", "&7- &f{command} &8[&7{mode}&8]",
                    "command", entry.command, "mode",
                    entry.action.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
    }

    private boolean isMode(String value) {
        return value.equalsIgnoreCase("block") || value.equalsIgnoreCase("alert-only")
                || value.equalsIgnoreCase("alert") || value.equalsIgnoreCase("allow")
                || value.equalsIgnoreCase("allowed");
    }

    private boolean valid(String command) {
        String normalized = plugin.getBlacklist().canonicalize(command);
        if (normalized.isBlank() || normalized.length() > 256 || normalized.indexOf('.') >= 0) return false;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) < 0x20) return false;
        }
        return true;
    }

    private String join(String[] args, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length)).trim();
    }

    private void send(CommandSender sender, String key, String def, Object... vars) {
        sender.sendMessage(plugin.getMsg().parse(render(fill(plugin.getLang().message(key, def), vars), true)));
    }

    private void sendRaw(CommandSender sender, String key, String def, Object... vars) {
        sender.sendMessage(plugin.getMsg().parse(render(fill(plugin.getLang().message(key, def), vars), false)));
    }

    private String fill(String text, Object... vars) {
        String out = text == null ? "" : text;
        for (int i = 0; i + 1 < vars.length; i += 2) {
            out = out.replace("{" + vars[i] + "}", plugin.getMsg().escape(String.valueOf(vars[i + 1])));
        }
        return out;
    }

    private String render(String text, boolean addPrefix) {
        String out = text == null ? "" : text;
        String prefix = plugin.getConfigModule().getString("prefix", "");
        if (prefix == null || prefix.isBlank()) prefix = plugin.getLang().get("prefix", "&7[&bACM&7]");
        return out.contains("%prefix%") ? out.replace("%prefix%", prefix)
                : (addPrefix ? prefix + " " + out : out);
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) if (option.toLowerCase(Locale.ROOT).startsWith(p)) out.add(option);
        return out;
    }
}
