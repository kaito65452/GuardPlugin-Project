package id.saryu.antichatmanager.listener;

import id.saryu.antichatmanager.blacklist.LiteBlacklistManager;
import id.saryu.antichatmanager.policy.PolicyStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Command interception listener for AntiChatManager Lite.
 *
 * <p>Dokumentasi Keamanan:
 * Event ini HANYA mencakup command pemain yang melewati PlayerCommandPreprocessEvent.
 * Event ini TIDAK mencakup console, command block, atau pemanggilan internal server.
 * Prioritas LOWEST dengan ignoreCancelled=false memastikan pemeriksaan manual isCancelled()
 * dan tidak membatalkan ulang command yang telah dibatalkan plugin lain.</p>
 */
public class LiteCommandListener implements Listener {

    private final JavaPlugin plugin;
    private final LiteBlacklistManager blacklist;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private boolean allowOpBypass = true;
    private String blockedMessage = "<red>Perintah '<white>{command}</white>' diblokir oleh administrator server.";

    public LiteCommandListener(@NotNull JavaPlugin plugin, @NotNull LiteBlacklistManager blacklist) {
        this.plugin = plugin;
        this.blacklist = blacklist;
    }

    public void reloadConfig(@NotNull FileConfiguration config, @NotNull FileConfiguration messages) {
        this.allowOpBypass = config.getBoolean("blacklist.bypass.allow-op", true);
        this.blockedMessage = messages.getString("command-blocked",
                "<red>Perintah '<white>{command}</white>' diblokir oleh administrator server.");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        // Cek isCancelled() manual dan jangan cancel ulang jika sudah dibatalkan
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();

        // Check administrative bypass permissions
        if (player.hasPermission("antichatmanager.admin") || player.hasPermission("antichatmanager.bypass")) {
            return;
        }
        if (allowOpBypass && player.isOp()) {
            return;
        }

        String raw = event.getMessage();
        if (raw == null || raw.isBlank()) {
            return;
        }

        // 2. Normalisasi: trim, spasi ganda digabung, huruf kecil, buang '/' awal
        String normalized = raw.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        normalized = normalized.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);

        if (normalized.isEmpty()) {
            return;
        }

        // Extract command root token
        int spaceIndex = normalized.indexOf(' ');
        String cmdToken = (spaceIndex > 0) ? normalized.substring(0, spaceIndex) : normalized;

        boolean blocked = false;

        // 1. Direct match on command token (namespace aware, literal match)
        if (blacklist.isBlocked(cmdToken)) {
            blocked = true;
        } else {
            // 2. Alias resolution via CommandMap
            Command resolved = resolveCommand(cmdToken);
            if (resolved != null) {
                String canonical = resolved.getName();
                List<String> aliases = resolved.getAliases();
                if (blacklist.isBlockedAlias(canonical, aliases)) {
                    blocked = true;
                }
            }
        }

        if (blocked) {
            event.setCancelled(true);

            // Sanitasi dan potong variabel dari input pemain untuk mencegah injeksi
            String sanitizedCmd = sanitizePlayerInput(cmdToken, 32);

            String formatted = blockedMessage.replace("{command}", sanitizedCmd)
                    .replace("{player}", player.getName());

            try {
                player.sendMessage(miniMessage.deserialize(formatted));
            } catch (Exception e) {
                player.sendMessage("§cPerintah '" + sanitizedCmd + "' diblokir oleh administrator server.");
            }

            if (blacklist.getStatus() == PolicyStatus.DEGRADED) {
                blacklist.alertDegraded("Pemblokiran dijalankan dalam mode DEGRADED untuk perintah: " + sanitizedCmd);
            }
        }
    }

    private @Nullable Command resolveCommand(@NotNull String token) {
        try {
            CommandMap map = Bukkit.getCommandMap();
            if (map != null) {
                String base = token;
                int colon = token.indexOf(':');
                if (colon >= 0) {
                    base = token.substring(colon + 1);
                }
                return map.getCommand(base);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private @NotNull String sanitizePlayerInput(@NotNull String input, int maxLength) {
        // Strip MiniMessage / formatting characters, truncate
        String clean = input.replaceAll("[<>§&]", "");
        if (clean.length() > maxLength) {
            clean = clean.substring(0, maxLength);
        }
        return clean;
    }
}
