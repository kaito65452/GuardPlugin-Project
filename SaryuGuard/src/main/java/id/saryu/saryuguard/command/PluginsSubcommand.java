package id.saryu.saryuguard.command;

import id.saryu.libcore.command.CommandFramework;
import id.saryu.libcore.ui.Paginator;
import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.verify.PluginVerifyInfo;
import id.saryu.saryuguard.verify.PluginVerifyModule;
import id.saryu.saryuguard.verify.VerifyStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class PluginsSubcommand implements CommandFramework.SubCommand {

    private final SaryuGuardPlugin plugin;

    public PluginsSubcommand(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String name() { return "plugins"; }
    @Override public @NotNull String permission() { return "saryuguard.plugins"; }
    @Override public String description() { return "Menampilkan daftar dan verifikasi integritas jar plugin"; }
    @Override public String usage() { return "/sg plugins [page|approve <plugin>]"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 1; }

    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        PluginVerifyModule verifyModule = (PluginVerifyModule) plugin.getModule("plugin-verify");
        if (verifyModule == null || !verifyModule.isEnabled()) {
            sender.sendMessage(plugin.getLocaleManager().parseComponent("<red>Modul Plugin Verify sedang nonaktif."));
            return;
        }

        // Handle: /sg plugins approve <plugin>
        if (args.length >= 2 && args[0].equalsIgnoreCase("approve")) {
            if (!sender.hasPermission("saryuguard.plugins.approve")) {
                plugin.getLocaleManager().send(sender, "core.no-permission", "<red>Anda tidak memiliki izin untuk approve hash plugin.");
                return;
            }
            String target = args[1];
            verifyModule.approvePlugin(target, sender.getName()).thenAccept(success -> {
                if (success) {
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<green>Plugin <white>" + target + "<green> berhasil disetujui! Baseline hash telah diperbarui."));
                } else {
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<red>Gagal menyetujui plugin <white>" + target + "<red>. Pastikan nama plugin sesuai."));
                }
            });
            return;
        }

        // Handle: /sg plugins [page]
        int page = 1;
        if (args.length >= 1) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        List<PluginVerifyInfo> list = new ArrayList<>(verifyModule.getVerifiedPlugins().values());
        list.sort(Comparator.comparing(a -> a.pluginName.toLowerCase(Locale.ROOT)));

        Paginator<PluginVerifyInfo> paginator = new Paginator<>(list, 7);
        Paginator.Language lang = plugin.getLocaleManager().getCurrentLocale().equalsIgnoreCase("en")
                ? Paginator.Language.ENGLISH
                : Paginator.Language.INDONESIAN;

        int safePage = paginator.clampPage(page);
        List<PluginVerifyInfo> pageItems = paginator.getPageItems(safePage);

        sender.sendMessage(plugin.getLocaleManager().parseComponent(
                "<gold>=== Verifikasi Plugin Server (" + list.size() + " total) ==="));

        if (pageItems.isEmpty()) {
            sender.sendMessage(plugin.getLocaleManager().parseComponent("<gray>Tidak ada data plugin yang ditemukan."));
        } else {
            for (PluginVerifyInfo info : pageItems) {
                String statusStr = info.status.formatted();
                String shortHash = info.sha256 != null && info.sha256.length() >= 8 ? info.sha256.substring(0, 8) : "????";

                Component line = Component.text("- ").color(NamedTextColor.GRAY)
                        .append(Component.text(info.pluginName).color(NamedTextColor.WHITE))
                        .append(Component.text(": ").color(NamedTextColor.GRAY))
                        .append(plugin.getLocaleManager().parseComponent(statusStr))
                        .append(Component.text(" [#" + shortHash + "]").color(NamedTextColor.DARK_GRAY)
                                .hoverEvent(HoverEvent.showText(Component.text("SHA-256: " + info.sha256 + "\n" + info.details))));

                if (info.status == VerifyStatus.MISMATCH || info.status == VerifyStatus.UNVERIFIED) {
                    Component approveBtn = Component.text(" [Setujui]")
                            .color(NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.runCommand("/sg plugins approve " + info.pluginName))
                            .hoverEvent(HoverEvent.showText(Component.text("Klik untuk menyetujui hash ini ke baseline lokal")));
                    line = line.append(approveBtn);
                }

                sender.sendMessage(line);
            }
        }

        // Paginator footer
        sender.sendMessage(paginator.buildFooter(safePage, "/sg plugins {n}", false, lang));
        sender.sendMessage(plugin.getLocaleManager().parseComponent(
                "<dark_gray><italic>* Catatan: VERIFIED berarti cocok dengan referensi resmi, bukan jaminan 100% bebas malware."));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> sub = new ArrayList<>(List.of("approve"));
            for (int i = 1; i <= 5; i++) sub.add(String.valueOf(i));
            return sub.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("approve")) {
            PluginVerifyModule vm = (PluginVerifyModule) plugin.getModule("plugin-verify");
            if (vm != null) {
                return vm.getVerifiedPlugins().keySet().stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }
        }
        return List.of();
    }
}
