package id.saryu.saryuguard.command;

import id.saryu.libcore.command.CommandFramework;
import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.scanner.MalwareScannerModule;
import id.saryu.saryuguard.verify.PluginVerifyInfo;
import id.saryu.saryuguard.verify.PluginVerifyModule;
import id.saryu.saryuguard.verify.VerifyStatus;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class ScanSubcommand implements CommandFramework.SubCommand {

    private final SaryuGuardPlugin plugin;

    public ScanSubcommand(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String name() { return "scan"; }
    @Override public @NotNull String permission() { return "saryuguard.scan"; }
    @Override public String description() { return "Memindai integritas jar dan keamanan server"; }
    @Override public String usage() { return "/sg scan <verify|malware [generate-script]>"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 3; }

    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("verify")) {
            PluginVerifyModule vm = (PluginVerifyModule) plugin.getModule("plugin-verify");
            if (vm == null || !vm.isEnabled()) {
                sender.sendMessage(plugin.getLocaleManager().parseComponent("<red>Modul Plugin Verify sedang nonaktif."));
                return;
            }

            sender.sendMessage(plugin.getLocaleManager().parseComponent("<gold>Memulai pemindaian integritas jar plugin..."));
            vm.scanAllAsync().thenRun(() -> {
                Collection<PluginVerifyInfo> results = vm.getVerifiedPlugins().values();
                int verified = 0;
                int unverified = 0;
                int mismatch = 0;
                int error = 0;

                for (PluginVerifyInfo r : results) {
                    if (r.status == VerifyStatus.VERIFIED) verified++;
                    else if (r.status == VerifyStatus.UNVERIFIED) unverified++;
                    else if (r.status == VerifyStatus.MISMATCH) mismatch++;
                    else if (r.status == VerifyStatus.SCAN_ERROR) error++;
                }

                sender.sendMessage(plugin.getLocaleManager().parseComponent(
                        "<green>Hasil Pemindaian Jar Plugin:"));
                sender.sendMessage(plugin.getLocaleManager().parseComponent(
                        "<gray>- Terverifikasi (VERIFIED): <green>" + verified));
                sender.sendMessage(plugin.getLocaleManager().parseComponent(
                        "<gray>- Belum diverifikasi (UNVERIFIED): <yellow>" + unverified));
                sender.sendMessage(plugin.getLocaleManager().parseComponent(
                        "<gray>- Tidak cocok (MISMATCH): <red>" + mismatch));
                if (error > 0) {
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<gray>- Gagal baca (SCAN_ERROR): <dark_red>" + error));
                }

                // Security posture check
                sender.sendMessage(plugin.getLocaleManager().parseComponent("<gold>Audit Keamanan Server:"));
                if (!Bukkit.getOnlineMode()) {
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<red>[CRITICAL] Server berjalan dalam offline-mode! Pastikan BungeeCord/Velocity secure forwarding aktif."));
                } else {
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<green>[OK] Server online-mode aktif."));
                }

                int opCount = Bukkit.getOperators().size();
                if (opCount > 3) {
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<yellow>[WARN] Terdapat " + opCount + " operator server terdaftar. Disarankan membatasi hak via LuckPerms."));
                } else {
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<green>[OK] Daftar operator server aman (" + opCount + " OPs)."));
                }

                sender.sendMessage(plugin.getLocaleManager().parseComponent(
                        "<gray>Gunakan <yellow>/sg plugins<gray> untuk detail per plugin."));
            });
            return;
        }

        if (args[0].equalsIgnoreCase("malware")) {
            MalwareScannerModule sm = (MalwareScannerModule) plugin.getModule("malware-scanner");
            if (sm == null) {
                sender.sendMessage(plugin.getLocaleManager().parseComponent("<red>Modul Malware Scanner tidak tersedia."));
                return;
            }

            if (args.length >= 2 && args[1].equalsIgnoreCase("generate-script")) {
                try {
                    Path p = sm.generateStartupScript();
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<green>Script pre-startup scan berhasil dibuat di: <white>" + p.toAbsolutePath()));
                } catch (Exception e) {
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<red>Gagal membuat script startup: " + e.getMessage()));
                }
                return;
            }

            if (!sm.isEnabled()) {
                sender.sendMessage(plugin.getLocaleManager().parseComponent(
                        "<red>Modul Malware Scanner belum aktif/degraded: " + sm.getStatusMessage()));
                return;
            }

            sender.sendMessage(plugin.getLocaleManager().parseComponent(
                    "<gold>Menjalankan pemindaian MCAntiMalware eksternal... Harap tunggu."));
            sm.runScanAsync().thenAccept(report -> {
                String durStr = (report.durationMs() / 1000.0) + "s";
                if (report.status() == MalwareScannerModule.ScanStatus.OK) {
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<green>[OK] Pemindaian malware selesai bersih (" + durStr + "). Tidak ada ancaman terdeteksi."));
                } else if (report.status() == MalwareScannerModule.ScanStatus.DETECTED) {
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<red><bold>[BAHAYA] Terdeteksi " + report.threatsFound() + " ancaman malware! (" + durStr + ")"));
                    for (String finding : report.findings()) {
                        sender.sendMessage(plugin.getLocaleManager().parseComponent("<red> * " + finding));
                    }
                } else {
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<red>Gagal menjalankan pemindaian malware (" + durStr + "): <gray>" + report.details()));
                }
            });
            return;
        }

        sender.sendMessage(plugin.getLocaleManager().parseComponent("<red>Penggunaan: /sg scan <verify|malware [generate-script]>"));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("verify", "malware").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("malware")) {
            return List.of("generate-script").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
