package id.saryu.antichatmanager.alert;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.libcore.SaryuLib;
import id.saryu.libcore.message.MessageModule;
import id.saryu.libcore.time.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages alert chat templates. When a player uses a blacklisted command, an
 * alert is broadcast to the configured target (global / staff-only / player-only)
 * using the selected template with variables replaced.
 */
public class AlertManager {

    private final AntiChatManager plugin;
    private final TimeUtil timeUtil;
    private String template;
    private String broadcastTarget;
    private String serverName;
    private String timezone;
    private String sound;
    private boolean enabled;

    // Cache of template strings: name -> raw multi-line string.
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public AlertManager(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
        this.timeUtil = new TimeUtil();
        reloadConfig();
    }

    public void reloadConfig() {
        enabled = plugin.getConfigModule().getBoolean("alert.enabled", true);
        template = plugin.getConfigModule().getString("alert.template", "boxed");
        broadcastTarget = plugin.getConfigModule().getString("alert.broadcast-target", "global");
        serverName = plugin.getConfigModule().getString("alert.server-name", "Server Security");
        timezone = plugin.getConfigModule().getString("timezone",
                plugin.getConfigModule().getString("alert.timezone", "WIB"));
        sound = plugin.getConfigModule().getString("alert.sound", "BLOCK_NOTE_BLOCK_HAT");
        loadTemplates();
    }

    private void loadTemplates() {
        templateCache.clear();
        // Templates live in the language files (templates.<name>) as required by
        // the spec. config.yml templates are optional per-install overrides.
        var langSection = plugin.getLang().section("templates");
        if (langSection != null) {
            for (String name : langSection.getKeys(false)) {
                String raw = langSection.getString(name);
                if (raw != null && !raw.isBlank()) templateCache.put(name, raw);
            }
        }
        var section = plugin.getConfigModule().get().getConfigurationSection("templates");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                String raw = section.getString(name);
                if (raw != null && !raw.isBlank()) templateCache.put(name, raw);
            }
        }
        // Ensure defaults exist.
        templateCache.putIfAbsent("boxed",
                "<#FF5555>=================================\n"
                + "<#FFFFFF>{PLAYER} <#AAAAAA>was detected trying to use\n"
                + "<#FFAA00>{CMDBLACKLIST} <#AAAAAA>on <#FFFFFF>{TIME}\n"
                + "<#5555FF>[{NAMESERVER}]\n"
                + "<#FF5555>=================================");
        templateCache.putIfAbsent("minimal",
                "<#5555FF>[{NAMESERVER}] <#FFFFFF>{PLAYER} <#AAAAAA>tried <#FFAA00>{CMDBLACKLIST}");
        templateCache.putIfAbsent("banner",
                "<gradient:#FF0000:#FFAA00>━━━━━ {NAMESERVER} ━━━━━</gradient>\n"
                + "<#FFFFFF>{PLAYER} <#AAAAAA>blocked from <#FFAA00>{CMDBLACKLIST}\n"
                + "<#AAAAAA>{TIME} • {WORLD}");
        templateCache.putIfAbsent("modern",
                "<gradient:#00C6FF:#0072FF>▎{NAMESERVER}</gradient>\n"
                + "<#FFFFFF>▎ {PLAYER} <#AAAAAA>attempted <#FFAA00>{CMDBLACKLIST}\n"
                + "<#555555>▎ {TIME}");
        templateCache.putIfAbsent("compact",
                "<#FF5555>⚠ <#FFFFFF>{PLAYER} <#AAAAAA>→ <#FFAA00>{CMDBLACKLIST} <#555555>({TIME})");
    }

    public void sendAlert(@NotNull Player player, @NotNull String matchedCommand,
                          @NotNull String rawInput, @NotNull String world) {
        if (!enabled) return;

        id.saryu.libcore.message.MessageModule.Placeholder placeholder = new id.saryu.libcore.message.MessageModule.Placeholder();
        Map<String, String> vars = new HashMap<>();
        vars.put("PLAYER", placeholder.unparsed(player.getName()));
        vars.put("CMDBLACKLIST", placeholder.unparsed(matchedCommand));
        vars.put("NAMESERVER", serverName);
        vars.put("INPUT", placeholder.unparsed(rawInput));
        vars.put("TIME", timeUtil.formatNow(timezone));
        vars.put("WORLD", placeholder.unparsed(world));
        var entry = plugin.getBlacklist().findEntry(matchedCommand);
        vars.put("SOURCE", entry != null ? entry.source.name() : "MANUAL");

        String rawTemplate = templateCache.getOrDefault(template, templateCache.get("boxed"));
        Set<Player> recipients = getRecipients(player);
        // Group recipients by their language so per-player locales get their own
        // template (when language-per-player is enabled).
        Map<String, List<Player>> byTemplate = new HashMap<>();
        for (Player p : recipients) {
            String localized = plugin.getLang().getForPlayer(p, "templates." + template);
            if (localized == null || localized.isBlank() || localized.equals("templates." + template)) {
                localized = rawTemplate;
            }
            byTemplate.computeIfAbsent(localized, k -> new java.util.ArrayList<>()).add(p);
        }
        for (Map.Entry<String, List<Player>> group : byTemplate.entrySet()) {
            // Template may be multi-line; send each line as a separate chat message.
            for (String line : group.getKey().split("\n")) {
                if (line.isBlank()) continue;
                String processed = line;
                for (Map.Entry<String, String> e : vars.entrySet()) {
                    processed = processed.replace("{" + e.getKey() + "}", e.getValue());
                }
                net.kyori.adventure.text.Component comp = plugin.getMsg().parse(processed);
                for (Player p : group.getValue()) {
                    p.sendMessage(comp);
                }
            }
        }
        // Play the alert sound once.
        if (!sound.isEmpty()) {
            try {
                org.bukkit.Sound s = org.bukkit.Sound.valueOf(sound);
                for (Player p : recipients) p.playSound(p.getLocation(), s, 1.0f, 1.0f);
            } catch (Exception ignored) {
            }
        }
    }

    private @NotNull Set<Player> getRecipients(@NotNull Player triggerPlayer) {
        Set<Player> recipients = ConcurrentHashMap.newKeySet();
        switch (broadcastTarget) {
            case "global":
                recipients.addAll(Bukkit.getOnlinePlayers());
                break;
            case "staff-only":
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("saryuantichatmanager.alerts")) recipients.add(p);
                }
                break;
            case "player-only":
                recipients.add(triggerPlayer);
                break;
            default:
                recipients.addAll(Bukkit.getOnlinePlayers());
        }
        return recipients;
    }

    public @NotNull String getTemplate() { return template; }
    public @NotNull String getBroadcastTarget() { return broadcastTarget; }
    public @NotNull String getServerName() { return serverName; }
    public @NotNull String getTimezone() { return timezone; }

    public void setTemplate(@NotNull String name) {
        if (templateCache.containsKey(name)) {
            template = name;
            plugin.getConfigModule().get().set("alert.template", name);
            plugin.getConfigModule().save();
        }
    }

    public @NotNull Map<String, String> getTemplates() {
        return new HashMap<>(templateCache);
    }

    public @Nullable String previewTemplate(@NotNull String name) {
        // Config overrides win; otherwise read the currently active language so
        // previewing after /acm language <code> reflects the new language.
        String raw = null;
        var section = plugin.getConfigModule().get().getConfigurationSection("templates");
        if (section != null) raw = section.getString(name);
        if (raw == null || raw.isBlank()) {
            String key = "templates." + name;
            raw = plugin.getLang().get(key);
            if (raw.equals(key)) raw = templateCache.get(name);
        }
        if (raw == null) return null;
        Map<String, String> vars = new HashMap<>();
        vars.put("PLAYER", "Steve");
        vars.put("CMDBLACKLIST", "gamemode");
        vars.put("NAMESERVER", serverName);
        vars.put("INPUT", "/gamemode creative Steve");
        vars.put("TIME", timeUtil.formatNow(timezone));
        vars.put("WORLD", "world");
        vars.put("SOURCE", "PLUGIN_SCAN");
        for (Map.Entry<String, String> e : vars.entrySet()) {
            raw = raw.replace("{" + e.getKey() + "}", e.getValue());
        }
        return raw;
    }
}
