package id.saryu.antichatmanager.api;

import id.saryu.antichatmanager.AntiChatManager;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Stable, small read API for other plugins in AntiChatManager Lite.
 */
public final class AntiChatManagerAPI {

    private final AntiChatManager plugin;

    public AntiChatManagerAPI(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
    }

    public boolean isBlacklisted(@NotNull String command) {
        return plugin.getBlacklistManager() != null && plugin.getBlacklistManager().isBlocked(command);
    }

    public boolean isAllowedFor(@NotNull Player player, @NotNull String command) {
        if (player.hasPermission("antichatmanager.admin") || player.hasPermission("antichatmanager.bypass")) {
            return true;
        }
        return !isBlacklisted(command);
    }

    public int blacklistSize() {
        return plugin.getBlacklistManager() != null ? plugin.getBlacklistManager().getSnapshot().size() : 0;
    }

    public @NotNull Set<String> getBlacklistedCommands() {
        return plugin.getBlacklistManager() != null
                ? plugin.getBlacklistManager().getSnapshot().getRawCommands()
                : Set.of();
    }

    public @NotNull String version() {
        return plugin.getBuildInfo() != null ? plugin.getBuildInfo().getVersion() : "4.0.0";
    }

    public void reload() {
        plugin.reloadLite();
    }
}
