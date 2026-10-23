package id.saryu.antichatmanager.packet;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.antichatmanager.blacklist.BlacklistManager;
import id.saryu.libcore.packet.PacketLayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Intercepts command/chat packets at the network layer (via PacketEvents) and
 * cancels blacklisted commands before they reach Bukkit.
 *
 * <p>This runs on a netty thread: the cancel decision is made synchronously (so
 * the packet never reaches the server), while alerts/punishment bookkeeping is
 * scheduled on the main thread.</p>
 */
public class PacketInterceptor implements PacketLayer.CommandInterceptor {

    private final AntiChatManager plugin;

    public PacketInterceptor(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull Player player, @NotNull String command) {
        if (plugin.canBypass(player)) return false;
        if (!plugin.getConfigModule().getBoolean("blacklist.enabled", true)) return false;

        int max = plugin.maxCommandLength();
        if (max > 0 && command.length() > max) {
            String raw = command.length() > 256 ? command.substring(0, 256) : command;
            plugin.getLib().getSchedulerModule().runSync(() ->
                    plugin.getCommandHandler().onBlockedCommand(player, raw, "command-too-long", "packet"));
            return true;
        }
        BlacklistManager.MatchResult result = plugin.getBlacklist().resolveWithNested(command);
        if (result == null || result.isAllow()) return false;
        if (plugin.getBlacklist().isAllowedFor(player, result.command)) return false;

        final String matchedCommand = result.command;
        final String raw = command.length() > 256 ? command.substring(0, 256) : command;
        if (result.isBlock()) {
            plugin.getLib().getSchedulerModule().runSync(() ->
                    plugin.getCommandHandler().onBlockedCommand(player, raw, matchedCommand, "packet"));
            return true;
        }
        // ALERT_ONLY: let the command execute, but notify staff asynchronously.
        plugin.getLib().getSchedulerModule().runSync(() ->
                plugin.getCommandHandler().onAlertOnly(player, raw, matchedCommand, "packet"));
        return false;
    }
}
