package id.saryu.antichatmanager.listener;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.antichatmanager.util.RateLimitModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

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
        String matched = plugin.getBlacklist().matchWithNested(raw);
        if (matched != null) {
            event.setCancelled(true);
            plugin.getCommandHandler().onBlockedCommand(player, raw, matched, "bukkit");
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
            if (plugin.getBlacklist().match(base) != null) {
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
            String matched = plugin.getBlacklist().matchWithNested(message);
            if (matched != null) {
                event.setCancelled(true);
                plugin.getCommandHandler().onBlockedCommand(player, message, matched, "chat");
                return;
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

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        plugin.getRateLimit().clear(event.getPlayer().getUniqueId());
    }

    private String abbreviate(@NotNull String value) {
        return value.length() > 64 ? value.substring(0, 64) + "..." : value;
    }
}
