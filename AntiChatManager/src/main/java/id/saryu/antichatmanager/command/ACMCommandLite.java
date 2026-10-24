package id.saryu.antichatmanager.command;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.antichatmanager.blacklist.LiteBlacklistManager;
import id.saryu.antichatmanager.policy.BlacklistSnapshot;
import id.saryu.antichatmanager.policy.PolicyStatus;
import id.saryu.libcore.command.CommandFramework;
import id.saryu.libcore.ui.Paginator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Command handler for AntiChatManager Lite 4.0.0 (/acm).
 *
 * <p>Commands:
 * - /acm version
 * - /acm blacklist info [page] | add <command> | remove <command>
 * - /acm reload
 *
 * Deprecated commands display a friendly notice pointing to SaryuGuard.</p>
 */
public class ACMCommandLite {

    public static final String PERM_ADMIN = "antichatmanager.admin";

    private final AntiChatManager plugin;
    private final LiteBlacklistManager blacklist;

    private static final Set<String> MOVED_TO_SARYUGUARD = Set.of(
            "plugins", "scan", "security", "conflicts", "audit", "subcmd",
            "integration", "history", "violations", "template", "export", "import", "language"
    );

    public ACMCommandLite(@NotNull AntiChatManager plugin, @NotNull LiteBlacklistManager blacklist) {
        this.plugin = plugin;
        this.blacklist = blacklist;
    }

    public void register(@NotNull CommandFramework framework) {
        CommandFramework.Root root = framework.root("acm", PERM_ADMIN, "AntiChatManager Lite administration");

        root.register(new VersionSub());
        root.register(new ReloadSub());
        root.register(new BlacklistSub());

        // Register redirects for removed subcommands
        for (String moved : MOVED_TO_SARYUGUARD) {
            root.register(new MovedRedirectSub(moved));
        }

        root.messages(new MessageSinkImpl());
        root.bind(plugin);
    }

    private final class MessageSinkImpl implements CommandFramework.MessageSink {
        @Override
        public void unknownSubcommand(@NotNull CommandSender sender, @NotNull String label,
                                     @NotNull String input, @Nullable String suggestion) {
            sender.sendMessage(plugin.formatMessage("command-unknown",
                    "<red>Subcommand tidak dikenal: <gray>{sub}. Ketik <yellow>/acm blacklist info<red> untuk bantuan.",
                    "sub", input));
        }

        @Override public void usage(@NotNull CommandSender sender, @NotNull String label, @NotNull String usage) {
            sender.sendMessage(plugin.formatMessage("command-usage", "<yellow>Penggunaan: <white>{usage}", "usage", usage));
        }

        @Override public void subcommands(@NotNull CommandSender sender, @NotNull String label, @NotNull Collection<String> subcommands) {
            sender.sendMessage(Component.text("=== AntiChatManager Lite 4.0.0 ===").color(NamedTextColor.GOLD));
            sender.sendMessage(Component.text(" - /acm version"));
            sender.sendMessage(Component.text(" - /acm blacklist info [page]"));
            sender.sendMessage(Component.text(" - /acm blacklist add <command>"));
            sender.sendMessage(Component.text(" - /acm blacklist remove <command>"));
            sender.sendMessage(Component.text(" - /acm reload"));
        }

        @Override public void noPermission(@NotNull CommandSender sender, boolean subcommand) {
            sender.sendMessage(plugin.formatMessage("no-permission", "<red>Anda tidak memiliki izin (antichatmanager.admin)."));
        }

        @Override public void playerOnly(@NotNull CommandSender sender) {
            sender.sendMessage(Component.text("Perintah ini hanya dapat dijalankan oleh pemain.").color(NamedTextColor.RED));
        }

        @Override public void cooldown(@NotNull CommandSender sender) {
            sender.sendMessage(Component.text("Harap tunggu beberapa detik.").color(NamedTextColor.RED));
        }

        @Override public void internalError(@NotNull CommandSender sender, @NotNull String label, @NotNull String subcommand, @NotNull Throwable error) {
            sender.sendMessage(Component.text("Kesalahan internal: " + error.getMessage()).color(NamedTextColor.RED));
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Subcommands                                                            */
    /* ---------------------------------------------------------------------- */

    public final class VersionSub implements CommandFramework.SubCommand {
        @Override public @NotNull String name() { return "version"; }
        @Override public @NotNull String permission() { return PERM_ADMIN; }
        @Override public String description() { return "Informasi versi AntiChatManager Lite"; }
        @Override public String usage() { return "/acm version"; }
        @Override public boolean playerOnly() { return false; }
        @Override public int cooldownSeconds() { return 0; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            String ver = plugin.getBuildInfo() != null ? plugin.getBuildInfo().getVersion() : "4.0.0";
            String commit = plugin.getBuildInfo() != null ? plugin.getBuildInfo().getCommit() : "unknown";
            PolicyStatus st = blacklist.getStatus();

            String statusColor = switch (st) {
                case READY -> "<green>READY</green>";
                case DEGRADED -> "<gold>DEGRADED</gold>";
                case UNAVAILABLE -> "<red>UNAVAILABLE</red>";
            };

            sender.sendMessage(plugin.formatMessage("version-info",
                    "<gold>AntiChatManager Lite <yellow>v{version} <gray>(Commit: {commit}) - Policy Status: {status}",
                    "version", ver,
                    "commit", commit,
                    "status", statusColor));
        }
    }

    public final class ReloadSub implements CommandFramework.SubCommand {
        @Override public @NotNull String name() { return "reload"; }
        @Override public @NotNull String permission() { return PERM_ADMIN; }
        @Override public String description() { return "Memuat ulang konfigurasi dan blacklist"; }
        @Override public String usage() { return "/acm reload"; }
        @Override public boolean playerOnly() { return false; }
        @Override public int cooldownSeconds() { return 2; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            sender.sendMessage(Component.text("Memuat ulang AntiChatManager Lite...").color(NamedTextColor.YELLOW));

            plugin.reloadLite().thenAccept(success -> {
                if (success && blacklist.getStatus() != PolicyStatus.UNAVAILABLE) {
                    sender.sendMessage(plugin.formatMessage("reload-success",
                            "<green>AntiChatManager Lite berhasil dimuat ulang! Status kebijakan: <white>{status}",
                            "status", blacklist.getStatus().name()));
                } else {
                    // /acm reload HARUS melapor gagal dan tidak boleh mengaku sukses jika policy UNAVAILABLE
                    sender.sendMessage(plugin.formatMessage("reload-failed",
                            "<red>Peringatan: Gagal memuat ulang kebijakan database! Kebijakan berada dalam status: <dark_red>{status}",
                            "status", blacklist.getStatus().name()));
                }
            });
        }
    }

    public final class BlacklistSub implements CommandFramework.SubCommand {
        @Override public @NotNull String name() { return "blacklist"; }
        @Override public @NotNull String permission() { return PERM_ADMIN; }
        @Override public String description() { return "Manajemen daftar hitam perintah"; }
        @Override public String usage() { return "/acm blacklist <info [page]|add <cmd>|remove <cmd>>"; }
        @Override public boolean playerOnly() { return false; }
        @Override public int cooldownSeconds() { return 0; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length == 0 || args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("list")) {
                int page = 1;
                if (args.length >= 2) {
                    try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
                }

                BlacklistSnapshot snap = blacklist.getSnapshot();
                List<String> commands = new ArrayList<>(snap.getRawCommands());
                commands.sort(String.CASE_INSENSITIVE_ORDER);

                Paginator<String> paginator = new Paginator<>(commands, 8);
                int safePage = paginator.clampPage(page);
                List<String> pageItems = paginator.getPageItems(safePage);

                sender.sendMessage(plugin.formatMessage("blacklist-header",
                        "<gold>=== Blacklist Commands ({count} entri) ===",
                        "count", String.valueOf(commands.size())));

                if (pageItems.isEmpty()) {
                    sender.sendMessage(Component.text("Daftar blacklist kosong.").color(NamedTextColor.GRAY));
                } else {
                    for (String c : pageItems) {
                        String src = snap.getSource(c);
                        if (src == null) src = "MANUAL";

                        Component line = Component.text("- ").color(NamedTextColor.GRAY)
                                .append(Component.text(c).color(NamedTextColor.WHITE))
                                .append(Component.text(" [" + src + "]").color(NamedTextColor.DARK_GRAY))
                                .append(Component.text(" [Hapus]").color(NamedTextColor.RED)
                                        .clickEvent(ClickEvent.runCommand("/acm blacklist remove " + c))
                                        .hoverEvent(HoverEvent.showText(Component.text("Klik untuk menghapus '" + c + "' dari blacklist"))));
                        sender.sendMessage(line);
                    }
                }

                sender.sendMessage(paginator.buildFooter(safePage, "/acm blacklist info {n}", false, Paginator.Language.INDONESIAN));
                return;
            }

            if (args[0].equalsIgnoreCase("add")) {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Penggunaan: /acm blacklist add <perintah>").color(NamedTextColor.RED));
                    return;
                }
                String toAdd = args[1];
                String validationError = blacklist.validateCommand(toAdd);
                if (validationError != null) {
                    sender.sendMessage(plugin.formatMessage("add-error", "<red>Gagal menambahkan: <gray>{error}", "error", validationError));
                    return;
                }

                blacklist.addCommand(toAdd, sender.getName()).thenAccept(success -> {
                    if (success) {
                        sender.sendMessage(plugin.formatMessage("add-success",
                                "<green>Perintah '<white>{cmd}</white>' berhasil ditambahkan ke blacklist.",
                                "cmd", toAdd));
                    } else {
                        sender.sendMessage(plugin.formatMessage("add-failed",
                                "<red>Gagal menyimpan perintah '<white>{cmd}</white>' ke database.",
                                "cmd", toAdd));
                    }
                });
                return;
            }

            if (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("del")) {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Penggunaan: /acm blacklist remove <perintah>").color(NamedTextColor.RED));
                    return;
                }
                String toRemove = args[1];
                blacklist.removeCommand(toRemove).thenAccept(success -> {
                    if (success) {
                        sender.sendMessage(plugin.formatMessage("remove-success",
                                "<green>Perintah '<white>{cmd}</white>' berhasil dihapus dari blacklist.",
                                "cmd", toRemove));
                    } else {
                        sender.sendMessage(plugin.formatMessage("remove-failed",
                                "<red>Perintah '<white>{cmd}</white>' tidak ditemukan atau gagal dihapus.",
                                "cmd", toRemove));
                    }
                });
                return;
            }

            sender.sendMessage(Component.text("Penggunaan: /acm blacklist <info [page]|add <cmd>|remove <cmd>>").color(NamedTextColor.RED));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String[] args) {
            if (args.length == 1) {
                return List.of("info", "add", "remove").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("del"))) {
                return blacklist.getSnapshot().getRawCommands().stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
                return List.of("1", "2", "3", "4", "5");
            }
            return List.of();
        }
    }

    /**
     * Subcommand redirecting deprecated ACM features to SaryuGuard.
     */
    public final class MovedRedirectSub implements CommandFramework.SubCommand {
        private final String subName;

        public MovedRedirectSub(@NotNull String subName) {
            this.subName = subName;
        }

        @Override public @NotNull String name() { return subName; }
        @Override public @NotNull String permission() { return PERM_ADMIN; }
        @Override public String description() { return "Dipindah ke SaryuGuard"; }
        @Override public String usage() { return "/acm " + subName; }
        @Override public boolean playerOnly() { return false; }
        @Override public int cooldownSeconds() { return 0; }

        @Override
        public void execute(CommandSender sender, Command cmd, String label, String[] args) {
            sender.sendMessage(Component.text("[ACM] Perintah '/acm " + subName
                    + "' telah dipindah ke SaryuGuard. Silakan gunakan perintah /sg.")
                    .color(NamedTextColor.YELLOW));
        }
    }
}
