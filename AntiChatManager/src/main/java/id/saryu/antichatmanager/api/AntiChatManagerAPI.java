package id.saryu.antichatmanager.api;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.antichatmanager.audit.PermissionAudit;
import id.saryu.antichatmanager.conflict.ConflictDetector;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Stable, small read API for other plugins. */
public final class AntiChatManagerAPI {

    private final AntiChatManager plugin;

    public AntiChatManagerAPI(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
    }

    public boolean isBlacklisted(@NotNull String command) {
        return plugin.getBlacklist().matchWithNested(command) != null;
    }

    public boolean isAllowedFor(@NotNull Player player, @NotNull String command) {
        String matched = plugin.getBlacklist().matchWithNested(command);
        return matched == null || plugin.getBlacklist().isAllowedFor(player, matched);
    }

    public int blacklistSize() {
        return plugin.getBlacklist().getAllEntries().size();
    }

    public @NotNull List<ConflictDetector.Conflict> conflicts() {
        return plugin.getConflictDetector() == null ? List.of() : plugin.getConflictDetector().getOpen();
    }

    public @NotNull List<PermissionAudit.Finding> auditFindings() {
        return plugin.getPermissionAudit() == null ? List.of() : plugin.getPermissionAudit().getOpen();
    }

    public @NotNull String version() {
        return plugin.getBuildInfo().getVersion();
    }

    public void reload() {
        plugin.reloadAll();
    }
}
