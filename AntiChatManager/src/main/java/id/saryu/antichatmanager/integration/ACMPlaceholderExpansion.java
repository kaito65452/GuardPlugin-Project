package id.saryu.antichatmanager.integration;

import id.saryu.antichatmanager.AntiChatManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** PlaceholderAPI bridge. Loaded only when PlaceholderAPI is installed. */
public final class ACMPlaceholderExpansion extends PlaceholderExpansion {

    private final AntiChatManager plugin;

    public ACMPlaceholderExpansion(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "acm"; }
    @Override public @NotNull String getAuthor() { return "Saryu"; }
    @Override public @NotNull String getVersion() { return plugin.getBuildInfo().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        org.bukkit.entity.Player online = player == null ? null
                : (player.isOnline() ? player.getPlayer() : null);
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "version" -> plugin.getBuildInfo().getVersion();
            case "language" -> online != null
                    ? plugin.getLang().getPlayerCode(online) : plugin.getLang().getActiveCode();
            case "blacklist_count" -> String.valueOf(plugin.getBlacklist().getAllEntries().size());
            case "violations" -> player == null ? "0"
                    : String.valueOf(plugin.getPunishmentTracker()
                    .getMemoryCount(player.getUniqueId().toString()));
            case "bypass" -> online == null ? "false" : String.valueOf(plugin.canBypass(online));
            case "conflicts_open" -> String.valueOf(plugin.getConflictDetector() == null
                    ? 0 : plugin.getConflictDetector().getOpen().size());
            case "conflicts_critical" -> String.valueOf(plugin.getConflictDetector() == null
                    ? 0 : plugin.getConflictDetector().getOpen().stream()
                    .filter(c -> c.severity == id.saryu.antichatmanager.conflict.ConflictDetector.Severity.CRITICAL)
                    .count());
            case "audit_open" -> String.valueOf(plugin.getPermissionAudit() == null
                    ? 0 : plugin.getPermissionAudit().getOpen().size());
            default -> null;
        };
    }
}
