package id.saryu.antichatmanager.command;

import id.saryu.antichatmanager.AntiChatManager;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Central handler for blocked/blacklisted commands. Sends the alert, records the
 * violation, applies punishment, and logs the event. Called from both the packet
 * interceptor and the Bukkit command preprocessor listener.
 */
public class CommandHandler {

    private final AntiChatManager plugin;

    public CommandHandler(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
    }

    public void onBlockedCommand(@NotNull CommandSender sender, @NotNull String rawInput,
                                 @NotNull String matchedCommand, @NotNull String source) {
        if (!(sender instanceof Player)) {
            // Console always allowed; just log.
            plugin.getLogger().info("Console used blacklisted command: " + rawInput);
            return;
        }
        Player player = (Player) sender;

        // Developer API. Cancelling cannot un-block the already-stopped command,
        // but it opts out of alerts/punishments (for example a trusted automation
        // plugin that handles its own notifications).
        boolean suppressConsequences = false;
        try {
            var event = new id.saryu.antichatmanager.api.CommandBlacklistedEvent(
                    player, rawInput, matchedCommand, source);
            org.bukkit.Bukkit.getPluginManager().callEvent(event);
            suppressConsequences = event.isCancelled();
        } catch (Throwable ignored) {
        }
        if (plugin.getAuditLog() != null) {
            plugin.getAuditLog().write(suppressConsequences ? "BLOCKED_SUPPRESSED" : "BLOCKED",
                    player.getName() + " " + player.getUniqueId()
                            + " command=" + matchedCommand + " source=" + source);
        }
        if (suppressConsequences) {
            plugin.getLogger().info("Blocked command for " + player.getName()
                    + " (alert/punishment suppressed by CommandBlacklistedEvent): " + matchedCommand);
            return;
        }

        // Backwards-compatible notification event (non-cancellable).
        try {
            org.bukkit.Bukkit.getPluginManager().callEvent(
                    new id.saryu.antichatmanager.api.BlockedCommandEvent(player, rawInput, matchedCommand, source));
        } catch (Throwable ignored) {
        }

        // Record violation.
        String world = player.getWorld().getName();
        plugin.getStorage().recordViolation(
                player.getUniqueId().toString(), player.getName(),
                matchedCommand, player.getServer().getName(), world, source);

        // Send alert chat.
        plugin.getAlertManager().sendAlert(player, matchedCommand, rawInput, world);

        // Track punishment escalation.
        plugin.getPunishmentTracker().record(player, matchedCommand);

        // Log to console.
        plugin.getLogger().warning(
                "Blacklist violation: " + player.getName() + " (" + player.getUniqueId() + ") "
                + "tried to use " + matchedCommand + " (source=" + source + ", world=" + world + ")");
    }

    public boolean isAdmin(@NotNull CommandSender sender) {
        return sender instanceof Player && plugin.isAdmin((Player) sender);
    }
}
