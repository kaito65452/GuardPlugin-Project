package id.saryu.saryuguard.command;

import id.saryu.libcore.command.CommandFramework;
import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.mfa.TwoFactorModule;
import id.saryu.saryuguard.module.SaryuGuardModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * {@code /sg 2fa <reset|resetsecret|status> [player]}
 *
 * <ul>
 *   <li>{@code reset} – console only: remove the LOCKED state so the player can log in again.</li>
 *   <li>{@code resetsecret} – console only: wipe the TOTP secret, forcing re-setup on next join.</li>
 *   <li>{@code status} – admin: show a player's current 2FA state.</li>
 * </ul>
 */
public class TwoFactorSubcommand implements CommandFramework.SubCommand {

    private final SaryuGuardPlugin plugin;

    public TwoFactorSubcommand(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String name() { return "2fa"; }
    @Override public @NotNull String permission() { return "saryuguard.admin"; }
    @Override public String description() { return "Kelola modul Staff 2FA"; }
    @Override public String usage() { return "/sg 2fa <reset|resetsecret|status> [player]"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 0; }

    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        TwoFactorModule mfa = getMfa();
        if (mfa == null) {
            sender.sendMessage(Component.text("[SaryuGuard] Modul 2FA tidak tersedia.", NamedTextColor.RED));
            return;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "help";
        switch (sub) {
            case "reset" -> {
                if (!(sender instanceof ConsoleCommandSender)) {
                    sender.sendMessage(Component.text(
                            "[SaryuGuard] Perintah ini hanya dapat dijalankan dari console.",
                            NamedTextColor.RED));
                    return;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Penggunaan: /sg 2fa reset <player>", NamedTextColor.YELLOW));
                    return;
                }
                UUID targetUuid = resolveUuid(args[1]);
                if (targetUuid == null) {
                    sender.sendMessage(Component.text("[SaryuGuard] Pemain tidak ditemukan: " + args[1], NamedTextColor.RED));
                    return;
                }
                boolean wasLocked = mfa.resetLock(targetUuid);
                sender.sendMessage(Component.text(
                        "[SaryuGuard] " + (wasLocked
                                ? "Lock 2FA untuk " + args[1] + " berhasil direset."
                                : args[1] + " tidak dalam status LOCKED."),
                        wasLocked ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            case "resetsecret" -> {
                if (!(sender instanceof ConsoleCommandSender)) {
                    sender.sendMessage(Component.text(
                            "[SaryuGuard] Perintah ini hanya dapat dijalankan dari console.",
                            NamedTextColor.RED));
                    return;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Penggunaan: /sg 2fa resetsecret <player>", NamedTextColor.YELLOW));
                    return;
                }
                UUID targetUuid = resolveUuid(args[1]);
                if (targetUuid == null) {
                    sender.sendMessage(Component.text("[SaryuGuard] Pemain tidak ditemukan: " + args[1], NamedTextColor.RED));
                    return;
                }
                boolean removed = mfa.resetSecret(targetUuid);
                sender.sendMessage(Component.text(
                        "[SaryuGuard] Secret 2FA untuk " + args[1]
                        + (removed ? " dihapus. Pemain harus setup ulang saat login." : " tidak ada."),
                        removed ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            case "status" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Penggunaan: /sg 2fa status <player>", NamedTextColor.YELLOW));
                    return;
                }
                UUID targetUuid = resolveUuid(args[1]);
                if (targetUuid == null) {
                    sender.sendMessage(Component.text("[SaryuGuard] Pemain tidak ditemukan: " + args[1], NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text("═══ 2FA Status: " + args[1] + " ═══", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("State: " + mfa.getPlayerState(targetUuid), NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("Secret terdaftar: " + mfa.hasSecret(targetUuid), NamedTextColor.YELLOW));
            }
            default -> {
                sender.sendMessage(Component.text("══ /sg 2fa ══", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("/sg 2fa reset <player>       - (Console) Reset lock 2FA", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("/sg 2fa resetsecret <player> - (Console) Hapus secret TOTP", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("/sg 2fa status <player>      - Cek status 2FA pemain", NamedTextColor.YELLOW));
            }
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return (sender instanceof ConsoleCommandSender)
                    ? List.of("reset", "resetsecret", "status")
                    : List.of("status");
        }
        return List.of();
    }

    private TwoFactorModule getMfa() {
        SaryuGuardModule m = plugin.getModule("2fa");
        return m instanceof TwoFactorModule ? (TwoFactorModule) m : null;
    }

    @SuppressWarnings("deprecation")
    private UUID resolveUuid(String name) {
        var online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        return op != null ? op.getUniqueId() : null;
    }
}
