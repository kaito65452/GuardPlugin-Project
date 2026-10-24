package id.saryu.saryuguard.command;

import id.saryu.libcore.command.CommandFramework;
import id.saryu.libcore.ui.Paginator;
import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.filter.ChatFilterModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WordsSubcommand implements CommandFramework.SubCommand {

    private final SaryuGuardPlugin plugin;

    public WordsSubcommand(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String name() { return "words"; }
    @Override public @NotNull String permission() { return "saryuguard.words"; }
    @Override public String description() { return "Manajemen daftar kata terlarang filter chat"; }
    @Override public String usage() { return "/sg words <list [page]|add <word>|remove <word>>"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 0; }

    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        ChatFilterModule filterModule = (ChatFilterModule) plugin.getModule("chat-filter");
        if (filterModule == null || !filterModule.isEnabled()) {
            sender.sendMessage(plugin.getLocaleManager().parseComponent("<red>Modul Chat Filter sedang nonaktif."));
            return;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            int page = 1;
            if (args.length >= 2) {
                try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
            }

            List<String> words = new ArrayList<>(filterModule.getBadWords());
            words.sort(String.CASE_INSENSITIVE_ORDER);

            Paginator<String> paginator = new Paginator<>(words, 8);
            Paginator.Language lang = plugin.getLocaleManager().getCurrentLocale().equalsIgnoreCase("en")
                    ? Paginator.Language.ENGLISH
                    : Paginator.Language.INDONESIAN;

            int safePage = paginator.clampPage(page);
            List<String> pageItems = paginator.getPageItems(safePage);

            sender.sendMessage(plugin.getLocaleManager().parseComponent(
                    "<gold>=== Daftar Kata Terlarang (" + words.size() + " total) ==="));

            if (pageItems.isEmpty()) {
                sender.sendMessage(plugin.getLocaleManager().parseComponent("<gray>Daftar kata kosong."));
            } else {
                for (String w : pageItems) {
                    Component line = Component.text("- ").color(NamedTextColor.GRAY)
                            .append(Component.text(w).color(NamedTextColor.WHITE))
                            .append(Component.text(" [Hapus]").color(NamedTextColor.RED)
                                    .clickEvent(ClickEvent.runCommand("/sg words remove " + w))
                                    .hoverEvent(HoverEvent.showText(Component.text("Klik untuk menghapus '" + w + "'"))));
                    sender.sendMessage(line);
                }
            }

            sender.sendMessage(paginator.buildFooter(safePage, "/sg words list {n}", false, lang));
            return;
        }

        if (args[0].equalsIgnoreCase("add")) {
            if (args.length < 2) {
                sender.sendMessage(plugin.getLocaleManager().parseComponent("<red>Penggunaan: /sg words add <kata>"));
                return;
            }
            String word = args[1].trim();
            if (filterModule.addWord(word)) {
                sender.sendMessage(plugin.getLocaleManager().parseComponent(
                        "<green>Kata '<white>" + word + "<green>' berhasil ditambahkan ke daftar filter chat."));
            } else {
                sender.sendMessage(plugin.getLocaleManager().parseComponent(
                        "<red>Kata '<white>" + word + "<red>' sudah ada di dalam daftar."));
            }
            return;
        }

        if (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("del")) {
            if (args.length < 2) {
                sender.sendMessage(plugin.getLocaleManager().parseComponent("<red>Penggunaan: /sg words remove <kata>"));
                return;
            }
            String word = args[1].trim();
            if (filterModule.removeWord(word)) {
                sender.sendMessage(plugin.getLocaleManager().parseComponent(
                        "<green>Kata '<white>" + word + "<green>' berhasil dihapus dari daftar filter chat."));
            } else {
                sender.sendMessage(plugin.getLocaleManager().parseComponent(
                        "<red>Kata '<white>" + word + "<red>' tidak ditemukan dalam daftar."));
            }
            return;
        }

        sender.sendMessage(plugin.getLocaleManager().parseComponent("<red>Penggunaan: /sg words <list [page]|add <word>|remove <word>>"));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("list", "add", "remove").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("del"))) {
            ChatFilterModule fm = (ChatFilterModule) plugin.getModule("chat-filter");
            if (fm != null) {
                return fm.getBadWords().stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }
        }
        return List.of();
    }
}
