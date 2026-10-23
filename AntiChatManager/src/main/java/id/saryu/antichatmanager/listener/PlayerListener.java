package id.saryu.antichatmanager.listener;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.antichatmanager.blacklist.BlacklistManager;
import id.saryu.antichatmanager.util.RateLimitModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Bukkit event listener: PlayerCommandPreprocessEvent (fallback command blocking),
 * PlayerCommandSendEvent (hides blacklisted commands from tab completion and the
 * client command list), AsyncPlayerChatEvent (command-looking chat, mainly for
 * Bedrock/Geyser clients) and PlayerQuitEvent (cleanup).
 */
public class PlayerListener implements Listener {

    private final AntiChatManager plugin;

    public PlayerListener(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandPreprocess(@NotNull PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        if (!plugin.getRateLimit().check(player, RateLimitModule.Kind.COMMAND)) {
            event.setCancelled(true);
            return;
        }
        if (plugin.canBypass(player)) return;

        String raw = event.getMessage();
        int max = plugin.maxCommandLength();
        if (max > 0 && raw.length() > max) {
            event.setCancelled(true);
            plugin.getCommandHandler().onBlockedCommand(player, abbreviate(raw), "command-too-long", "length");
            return;
        }
        BlacklistManager.MatchResult result = plugin.getBlacklist().resolveWithNested(raw);
        if (result != null && !result.isAllow()
                && !plugin.getBlacklist().isAllowedFor(player, result.command)) {
            if (result.isBlock()) {
                event.setCancelled(true);
                plugin.getCommandHandler().onBlockedCommand(player, raw, result.command, "bukkit");
            } else if (result.isAlertOnly()) {
                plugin.getCommandHandler().onAlertOnly(player, raw, result.command, "bukkit");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandSend(@NotNull PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        if (!plugin.getRateLimit().check(player, RateLimitModule.Kind.TAB)) {
            event.getCommands().clear();
            return;
        }
        // OPs/staff see the full command list.
        if (plugin.canBypass(player)) return;

        Set<String> hidden = new HashSet<>();
        event.getCommands().removeIf(command -> {
            String base = plugin.getBlacklist().stripNamespace(command.toLowerCase());
            BlacklistManager.MatchResult result = plugin.getBlacklist().resolve(base);
            if (result != null && result.isBlock()) {
                hidden.add(base);
                return true;
            }
            return false;
        });
        if (!hidden.isEmpty()) {
            plugin.getLib().getPacketLayer().sendTabListFilter(player, hidden);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(@NotNull AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        if (!plugin.getRateLimit().check(player, RateLimitModule.Kind.CHAT)) {
            event.setCancelled(true);
            return;
        }
        if (plugin.canBypass(player)) return;

        String message = event.getMessage();
        if (message == null) return;

        // Geyser/Floodgate command attempts can arrive as chat text.
        if (message.startsWith("/")) {
            BlacklistManager.MatchResult result = plugin.getBlacklist().resolveWithNested(message);
            if (result != null && !result.isAllow()
                    && !plugin.getBlacklist().isAllowedFor(player, result.command)) {
                if (result.isBlock()) {
                    event.setCancelled(true);
                    plugin.getCommandHandler().onBlockedCommand(player, message, result.command, "chat");
                    return;
                }
                if (result.isAlertOnly()) {
                    plugin.getCommandHandler().onAlertOnly(player, message, result.command, "chat");
                }
            }
        }

        if (plugin.getConfigModule().getBoolean("security.block-raw-section", true)
                && message.indexOf('\u00a7') >= 0) {
            event.setCancelled(true);
            plugin.getCommandHandler().onBlockedCommand(player, abbreviate(message), "section-sign", "security");
            return;
        }
        if (plugin.getConfigModule().getBoolean("security.block-zero-width", true)) {
            for (int i = 0; i < message.length(); i++) {
                char c = message.charAt(i);
                if (c >= '\u200B' && c <= '\u200D' || c == '\uFEFF') {
                    event.setCancelled(true);
                    plugin.getCommandHandler().onBlockedCommand(player, abbreviate(message), "zero-width", "security");
                    return;
                }
            }
        }
        if (plugin.getConfigModule().getBoolean("security.block-control-chars", true)) {
            for (int i = 0; i < message.length(); i++) {
                char c = message.charAt(i);
                if (c < 0x20 && c != '\n' && c != '\t') {
                    event.setCancelled(true);
                    plugin.getCommandHandler().onBlockedCommand(player, abbreviate(message), "control-char", "security");
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(@NotNull org.bukkit.event.server.ServerCommandEvent event) {
        String raw = event.getCommand();
        if (raw == null || raw.isBlank()) return;
        boolean block = false;
        if (event.getSender() instanceof org.bukkit.command.BlockCommandSender) {
            block = !plugin.getConfigModule().getBoolean("blacklist.sources.command-block", false);
        } else if (event.getSender() instanceof org.bukkit.command.RemoteConsoleCommandSender) {
            block = !plugin.getConfigModule().getBoolean("blacklist.sources.rcon", false);
        }
        BlacklistManager.MatchResult result = plugin.getBlacklist().resolveWithNested(raw);
        if (result != null && block) {
            if (result.isBlock()) {
                event.setCancelled(true);
                plugin.getLogger().warning("Blocked blacklisted command from " + event.getSender().getName()
                        + ": " + abbreviate(raw));
                if (event.getSender() instanceof Player p) {
                    plugin.getAlertManager().sendAlert(p, result.command, raw, "server");
                }
            } else if (result.isAlertOnly() && event.getSender() instanceof Player p) {
                plugin.getCommandHandler().onAlertOnly(p, raw, result.command, "server");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEditBook(@NotNull org.bukkit.event.player.PlayerEditBookEvent event) {
        if (event.getPlayer() == null || plugin.canBypass(event.getPlayer())) return;
        if (event.getNewBookMeta() == null) return;
        for (String page : event.getNewBookMeta().getPages()) {
            if (unsafeInteractiveText(page)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSignChange(@NotNull org.bukkit.event.block.SignChangeEvent event) {
        if (event.getPlayer() == null || plugin.canBypass(event.getPlayer())) return;
        for (String line : event.getLines()) {
            if (line == null) continue;
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(plugin.getMsg().parse(line));
            if (unsafeInteractiveText(plain) || isBlockedCommand(plain)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private boolean unsafeInteractiveText(@Nullable String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("<click:") || lower.contains("clickevent") || lower.contains("run_command")
                || lower.contains("suggest_command") || lower.contains("open_url")) return true;
        return isBlockedCommand(text);
    }

    private boolean isBlockedCommand(@NotNull String text) {
        BlacklistManager.MatchResult result = plugin.getBlacklist().resolveWithNested(text);
        return result != null && result.isBlock();
    }

    /**
     * Optional subcommand filtering. Bukkit/Paper does not send command argument
     * completions through the command-list packet, so this removes suggestions
     * that would execute a blocked rule. Alert-only and allow rules remain
     * visible; a bypass permission sees the normal list.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTabComplete(@NotNull TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player player)) return;
        if (plugin.canBypass(player)) return;
        if (!plugin.getRateLimit().check(player, RateLimitModule.Kind.TAB)) {
            event.setCancelled(true);
            return;
        }
        String buffer = event.getBuffer();
        if (buffer == null || !buffer.startsWith("/") || buffer.length() > plugin.maxCommandLength()) return;
        int space = buffer.lastIndexOf(' ');
        String prefix = space >= 0 ? buffer.substring(0, space + 1) : buffer;
        event.getCompletions().removeIf(completion -> {
            if (completion == null || completion.isBlank()) return false;
            BlacklistManager.MatchResult result = plugin.getBlacklist().resolve(prefix + completion);
            return result != null && result.isBlock();
        });
    }

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        if (!player.hasPermission("saryuantichatmanager.alerts")) return;
        plugin.getLib().getSchedulerModule().runSyncLater(() -> {
            try {
                if (plugin.getConflictDetector() != null && plugin.getConflictDetector().isEnabled()) {
                    var open = plugin.getConflictDetector().getOpen();
                    if (!open.isEmpty()) {
                        String msg = plugin.getLang().message("conflicts.reminder",
                                "&c[Security] {count} LuckPerms conflict(s) remain open. Run &f/acm conflicts&7 to review (blacklist will NOT be removed automatically).")
                                .replace("{count}", String.valueOf(open.size()));
                        player.sendMessage(plugin.getMsg().parse(msg));
                    }
                }
            } catch (Throwable ignored) {}
            try {
                if (plugin.getPermissionAudit() != null && plugin.getPermissionAudit().isEnabled()) {
                    var open = plugin.getPermissionAudit().getOpen();
                    if (!open.isEmpty()) {
                        String msg = plugin.getLang().message("audit.reminder",
                                "&c[Security Audit] {count} permission finding(s) need review. Run &f/acm audit&7.")
                                .replace("{count}", String.valueOf(open.size()));
                        player.sendMessage(plugin.getMsg().parse(msg));
                    }
                }
            } catch (Throwable ignored) {}
        }, 40L);
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        plugin.getRateLimit().clear(event.getPlayer().getUniqueId());
    }

    private String abbreviate(@NotNull String value) {
        return value.length() > 64 ? value.substring(0, 64) + "..." : value;
    }
}
