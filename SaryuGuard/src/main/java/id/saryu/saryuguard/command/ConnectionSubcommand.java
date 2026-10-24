package id.saryu.saryuguard.command;

import id.saryu.libcore.command.CommandFramework;
import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.connection.ConnectionGuardModule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Subcommand for /sg connection.
 */
public class ConnectionSubcommand implements CommandFramework.SubCommand {

    private final SaryuGuardPlugin plugin;

    public ConnectionSubcommand(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String name() { return "connection"; }
    @Override public @NotNull String permission() { return "saryuguard.connection"; }
    @Override public String description() { return "Menampilkan status perlindungan koneksi dan matriks kompatibilitas"; }
    @Override public String usage() { return "/sg connection"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 1; }

    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        ConnectionGuardModule mod = (ConnectionGuardModule) plugin.getModule("connection-guard");
        if (mod == null || !mod.isEnabled()) {
            sender.sendMessage(plugin.getLocaleManager().parseComponent("<red>Modul Connection Guard sedang nonaktif."));
            return;
        }

        sender.sendMessage(plugin.getLocaleManager().parseComponent("<gold>=== Status Connection Guard & Kompatibilitas ==="));
        sender.sendMessage(plugin.getLocaleManager().parseComponent("<gray>Status Operasional: " + mod.getStatus().formatted()));

        // Matriks Kompatibilitas
        sender.sendMessage(plugin.getLocaleManager().parseComponent("<yellow>Matriks Kompatibilitas Dinamis:"));
        String sonarStatus = mod.isDelegateThrottleToSonar()
                ? "<green>Delegasi ke Sonar (Passive Internal)</green>"
                : "<white>Aktif Internal (Full Protection)</white>";
        String authMeStatus = mod.isDelegateAccountToAuthMe()
                ? "<green>Delegasi ke AuthMe (Passive Internal)</green>"
                : "<white>Aktif Internal (Full Protection)</white>";

        sender.sendMessage(plugin.getLocaleManager().parseComponent("<gray>- Sonar (Throttle & Ping): " + sonarStatus));
        sender.sendMessage(plugin.getLocaleManager().parseComponent("<gray>- AuthMe (Akun & Username): " + authMeStatus));

        // Attack Mode
        String attackStatus = mod.isUnderAttack()
                ? "<red><bold>SEDANG AKTIF</bold></red> (Mitigasi bot newcomer)"
                : "<green>Standby</green>";
        sender.sendMessage(plugin.getLocaleManager().parseComponent("<gray>- Mode Serangan: " + attackStatus));

        // Metrik
        sender.sendMessage(plugin.getLocaleManager().parseComponent("<yellow>Metrik Langsung:"));
        sender.sendMessage(plugin.getLocaleManager().parseComponent("<gray>- Total koneksi diperiksa: <white>" + mod.getTotalChecked()));
        sender.sendMessage(plugin.getLocaleManager().parseComponent("<gray>- Koneksi dibatasi (throttled): <yellow>" + mod.getThrottledConnections()));
        sender.sendMessage(plugin.getLocaleManager().parseComponent("<gray>- Username tidak valid ditolak: <red>" + mod.getBlockedUsernames()));
        sender.sendMessage(plugin.getLocaleManager().parseComponent("<gray>- Ping flood diredam: <gold>" + mod.getBlockedPings()));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
