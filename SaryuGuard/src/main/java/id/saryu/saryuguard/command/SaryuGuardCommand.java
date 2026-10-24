package id.saryu.saryuguard.command;

import id.saryu.libcore.command.CommandFramework;
import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.module.ModuleStatus;
import id.saryu.saryuguard.module.SaryuGuardModule;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Root command handler for /sg (/saryuguard).
 */
public class SaryuGuardCommand {

    private final SaryuGuardPlugin plugin;

    public SaryuGuardCommand(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(@NotNull CommandFramework framework) {
        CommandFramework.Root root = framework.root("sg", "", "SaryuGuard administration");

        root.register(new HelpSubcommand());
        root.register(new StatusSubcommand());
        root.register(new ReloadSubcommand());
        root.register(new VersionSubcommand());
        root.register(new PluginsSubcommand(plugin));
        root.register(new ScanSubcommand(plugin));
        root.register(new WordsSubcommand(plugin));
        root.register(new OpGuardSubcommand(plugin));
        root.register(new TwoFactorSubcommand(plugin));
        root.register(new CryptSubcommand(plugin));
        root.register(new AuditSubcommand(plugin));
        root.register(new ConnectionSubcommand(plugin));

        root.messages(new MessageSinkImpl());
        root.bind(plugin);
    }

    private final class MessageSinkImpl implements CommandFramework.MessageSink {
        @Override
        public void unknownSubcommand(@NotNull CommandSender sender, @NotNull String label,
                                     @NotNull String input, @Nullable String suggestion) {
            plugin.getLocaleManager().send(sender, "core.unknown-subcommand",
                    "<red>Subcommand tidak dikenal: <gray>{sub}. Ketik <yellow>/sg help<red> untuk bantuan.",
                    "sub", input);
        }

        @Override
        public void usage(@NotNull CommandSender sender, @NotNull String label, @NotNull String usage) {
            plugin.getLocaleManager().send(sender, "core.usage", "<yellow>Penggunaan: <white>{usage}",
                    "usage", usage);
        }

        @Override
        public void subcommands(@NotNull CommandSender sender, @NotNull String label,
                                @NotNull Collection<String> subcommands) {
            sender.sendMessage(plugin.getLocaleManager().message("help.header", "<gold>=== Bantuan Perintah SaryuGuard ==="));
            for (String sub : subcommands) {
                sender.sendMessage(Component.text(" - /sg " + sub));
            }
        }

        @Override
        public void noPermission(@NotNull CommandSender sender, boolean subcommand) {
            plugin.getLocaleManager().send(sender, "core.no-permission",
                    "<red>Anda tidak memiliki izin untuk menjalankan perintah ini.");
        }

        @Override
        public void playerOnly(@NotNull CommandSender sender) {
            plugin.getLocaleManager().send(sender, "core.player-only",
                    "<red>Perintah ini hanya dapat dijalankan oleh pemain.");
        }

        @Override
        public void cooldown(@NotNull CommandSender sender) {
            plugin.getLocaleManager().send(sender, "core.cooldown",
                    "<red>Harap tunggu beberapa saat sebelum menjalankan perintah ini kembali.");
        }

        @Override
        public void internalError(@NotNull CommandSender sender, @NotNull String label,
                                  @NotNull String subcommand, @NotNull Throwable error) {
            plugin.getLocaleManager().send(sender, "core.error",
                    "<red>Terjadi kesalahan internal saat menjalankan /sg {sub}: <gray>{msg}",
                    "sub", subcommand, "msg", error.getMessage());
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Subcommands                                                            */
    /* ---------------------------------------------------------------------- */

    public final class HelpSubcommand implements CommandFramework.SubCommand {
        @Override public @NotNull String name() { return "help"; }
        @Override public @NotNull String permission() { return "saryuguard.help"; }
        @Override public String description() { return "Menampilkan daftar perintah SaryuGuard"; }
        @Override public String usage() { return "/sg help"; }
        @Override public boolean playerOnly() { return false; }
        @Override public int cooldownSeconds() { return 0; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            sender.sendMessage(plugin.getLocaleManager().message("help.header", "<gold>=== Bantuan Perintah SaryuGuard ==="));
            sender.sendMessage(plugin.getLocaleManager().message("help.help", "<yellow>/sg help <gray>- Menampilkan menu bantuan"));
            sender.sendMessage(plugin.getLocaleManager().message("help.status", "<yellow>/sg status <gray>- Menampilkan status operasional modul"));
            sender.sendMessage(plugin.getLocaleManager().message("help.reload", "<yellow>/sg reload <gray>- Memuat ulang konfigurasi dan modul"));
            sender.sendMessage(plugin.getLocaleManager().message("help.version", "<yellow>/sg version <gray>- Menampilkan informasi versi plugin"));
            sender.sendMessage(plugin.getLocaleManager().message("help.plugins", "<yellow>/sg plugins [page] <gray>- Menampilkan verifikasi jar plugin"));
            sender.sendMessage(plugin.getLocaleManager().message("help.plugins-approve", "<yellow>/sg plugins approve <plugin> <gray>- Menyetujui hash jar"));
            sender.sendMessage(plugin.getLocaleManager().message("help.scan-verify", "<yellow>/sg scan verify <gray>- Memeriksa integritas jar"));
            sender.sendMessage(plugin.getLocaleManager().message("help.scan-malware", "<yellow>/sg scan malware <gray>- Menjalankan scanner eksternal"));
            sender.sendMessage(plugin.getLocaleManager().message("help.opguard", "<yellow>/sg opguard <gray>- Manajemen perlindungan operator"));
            sender.sendMessage(plugin.getLocaleManager().message("help.mfa", "<yellow>/sg 2fa <gray>- Manajemen autentikasi 2FA"));
            sender.sendMessage(plugin.getLocaleManager().message("help.words", "<yellow>/sg words <gray>- Manajemen daftar kata filter"));
            sender.sendMessage(plugin.getLocaleManager().message("help.audit", "<yellow>/sg audit [page|verify] <gray>- Log audit dan verifikasi"));
            sender.sendMessage(plugin.getLocaleManager().message("help.connection", "<yellow>/sg connection <gray>- Perlindungan koneksi"));
            sender.sendMessage(plugin.getLocaleManager().message("help.crypt", "<yellow>/sg crypt <gray>- Enkripsi dan rotasi kunci"));
        }
    }

    public final class StatusSubcommand implements CommandFramework.SubCommand {
        @Override public @NotNull String name() { return "status"; }
        @Override public @NotNull String permission() { return "saryuguard.status"; }
        @Override public String description() { return "Menampilkan status modul dan saran pemula"; }
        @Override public String usage() { return "/sg status"; }
        @Override public boolean playerOnly() { return false; }
        @Override public int cooldownSeconds() { return 0; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            sender.sendMessage(plugin.getLocaleManager().message("status.header", "<gold>=== Status Sistem SaryuGuard ==="));

            Collection<SaryuGuardModule> modules = plugin.getModules();
            if (modules.isEmpty()) {
                sender.sendMessage(plugin.getLocaleManager().parseComponent("<gray>Belum ada modul tambahan yang terdaftar."));
            } else {
                for (SaryuGuardModule mod : modules) {
                    ModuleStatus st = mod.getStatus();
                    String statusFormatted = plugin.getLocaleManager().getRaw("modules.status." + st.name().toLowerCase(Locale.ROOT), st.formatted());
                    sender.sendMessage(plugin.getLocaleManager().message("status.line",
                            "<gray>- <white>{module}: {status} <dark_gray>({info})",
                            "module", mod.displayName(),
                            "status", statusFormatted,
                            "info", mod.getStatusMessage()));
                }
            }

            // Tips for beginners
            sender.sendMessage(Component.empty());
            sender.sendMessage(plugin.getLocaleManager().message("status.tip-title", "<yellow>Saran untuk Pemula:"));
            List<String> tips = plugin.getLocaleManager().getRawList("status.tips");
            if (tips == null || tips.isEmpty()) {
                tips = List.of(
                        "Pastikan file secret.key dicadangkan bersama database Anda.",
                        "Daftarkan UUID staf terpercaya di opguard untuk mencegah backdoor OP.",
                        "Aktifkan 2FA untuk akun berhak tinggi guna mencegah pembajakan akun."
                );
            }
            for (String tip : tips) {
                sender.sendMessage(plugin.getLocaleManager().message("status.tip-line", "<gray>* <white>{tip}", "tip", tip));
            }

            sender.sendMessage(plugin.getLocaleManager().message("status.footer", "<gray>Gunakan <yellow>/sg help<gray> untuk melihat perintah."));
        }
    }

    public final class ReloadSubcommand implements CommandFramework.SubCommand {
        @Override public @NotNull String name() { return "reload"; }
        @Override public @NotNull String permission() { return "saryuguard.reload"; }
        @Override public String description() { return "Memuat ulang konfigurasi dan modul"; }
        @Override public String usage() { return "/sg reload"; }
        @Override public boolean playerOnly() { return false; }
        @Override public int cooldownSeconds() { return 3; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            try {
                plugin.reloadAll();
                plugin.getLocaleManager().send(sender, "core.reload-success",
                        "<green>Konfigurasi dan modul SaryuGuard berhasil dimuat ulang.");
            } catch (Exception e) {
                plugin.getLocaleManager().send(sender, "core.reload-failed",
                        "<red>Gagal memuat ulang: <gray>{error}", "error", e.getMessage());
            }
        }
    }

    public final class VersionSubcommand implements CommandFramework.SubCommand {
        @Override public @NotNull String name() { return "version"; }
        @Override public @NotNull String permission() { return "saryuguard.version"; }
        @Override public String description() { return "Menampilkan versi SaryuGuard"; }
        @Override public String usage() { return "/sg version"; }
        @Override public boolean playerOnly() { return false; }
        @Override public int cooldownSeconds() { return 0; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            String ver = plugin.getBuildInfo() != null ? plugin.getBuildInfo().getVersion() : plugin.getDescription().getVersion();
            String commit = plugin.getBuildInfo() != null ? plugin.getBuildInfo().getCommit() : "unknown";
            String channel = plugin.getBuildInfo() != null ? plugin.getBuildInfo().getChannel() : "dev";
            String javaVer = System.getProperty("java.version");

            sender.sendMessage(plugin.getLocaleManager().message("version.info",
                    "<gold>SaryuGuard <yellow>v{version} <gray>(Commit: {commit}, Channel: {channel}) running on Java {java}.",
                    "version", ver,
                    "commit", commit,
                    "channel", channel,
                    "java", javaVer));
        }
    }
}
