package id.saryu.saryuguard.command;

import id.saryu.libcore.command.CommandFramework;
import id.saryu.libcore.ui.Paginator;
import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.audit.AuditDao;
import id.saryu.saryuguard.audit.AuditModule;
import id.saryu.saryuguard.database.SaryuGuardDatabase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Subcommand for /sg audit [page|verify].
 */
public class AuditSubcommand implements CommandFramework.SubCommand {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SaryuGuardPlugin plugin;

    public AuditSubcommand(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String name() { return "audit"; }
    @Override public @NotNull String permission() { return "saryuguard.audit"; }
    @Override public String description() { return "Melihat catatan audit dan memverifikasi integritas hash chain"; }
    @Override public String usage() { return "/sg audit [page|verify]"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 1; }

    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        AuditModule audit = (AuditModule) plugin.getModule("audit");
        if (audit == null || !audit.isEnabled()) {
            sender.sendMessage(plugin.getLocaleManager().parseComponent("<red>Modul Audit & Forensics sedang nonaktif."));
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("verify")) {
            sender.sendMessage(plugin.getLocaleManager().parseComponent("<gold>Memverifikasi integritas kriptografis rantai hash audit..."));
            plugin.getDatabase().async(c -> {
                try {
                    return audit.verifyChain();
                } catch (Exception e) {
                    return AuditModule.VerifyResult.failed(e.getMessage());
                }
            }).thenAccept(res -> {
                if (res.ok()) {
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<green>Integritas rantai audit: <bold>VALID</bold>. "
                                    + res.checked() + " entri diverifikasi tanpa modifikasi (Head: <gray>"
                                    + res.headHash().substring(0, Math.min(8, res.headHash().length())) + "…</gray>)."));
                } else {
                    sender.sendMessage(plugin.getLocaleManager().parseComponent(
                            "<red><bold>[PERINGATAN]</bold> Integritas rantai audit <bold>GAGAL</bold>: "
                                    + res.error()));
                }
            });
            return;
        }

        // Paging audit logs
        int page = 1;
        if (args.length >= 1) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        final int targetPage = page;
        SaryuGuardDatabase db = plugin.getDatabase();
        if (db == null || !db.isAvailable()) {
            sender.sendMessage(plugin.getLocaleManager().parseComponent("<red>Database tidak tersedia."));
            return;
        }

        db.async(c -> {
            AuditDao dao = new AuditDao();
            return dao.listRecent(c, 70);
        }).thenAccept(rows -> {
            List<AuditDao.StoredRow> list = new ArrayList<>(rows);
            Paginator<AuditDao.StoredRow> paginator = new Paginator<>(list, 7);
            Paginator.Language lang = plugin.getLocaleManager().getCurrentLocale().equalsIgnoreCase("en")
                    ? Paginator.Language.ENGLISH
                    : Paginator.Language.INDONESIAN;

            int safePage = paginator.clampPage(targetPage);
            List<AuditDao.StoredRow> pageItems = paginator.getPageItems(safePage);

            sender.sendMessage(plugin.getLocaleManager().parseComponent(
                    "<gold>=== Log Audit Forensik SaryuGuard (" + list.size() + " terbaru) ==="));

            if (pageItems.isEmpty()) {
                sender.sendMessage(plugin.getLocaleManager().parseComponent("<gray>Belum ada catatan audit tersimpan."));
            } else {
                for (AuditDao.StoredRow row : pageItems) {
                    String timeStr = TIME_FORMAT.format(Instant.ofEpochMilli(row.createdAt()));
                    String actor = row.actorName() != null ? row.actorName() : "SYSTEM";
                    String shortHash = row.chainHash() != null && row.chainHash().length() >= 8
                            ? row.chainHash().substring(0, 8) : "???";

                    Component line = Component.text("[" + timeStr + "] ").color(NamedTextColor.DARK_GRAY)
                            .append(Component.text(row.eventType()).color(NamedTextColor.YELLOW))
                            .append(Component.text(" oleh ").color(NamedTextColor.GRAY))
                            .append(Component.text(actor).color(NamedTextColor.WHITE))
                            .append(Component.text(" [#" + shortHash + "]").color(NamedTextColor.DARK_GRAY)
                                    .hoverEvent(HoverEvent.showText(Component.text("Target: " + row.targetName()
                                            + "\nChain Hash: " + row.chainHash()))));

                    sender.sendMessage(line);
                }
            }

            sender.sendMessage(paginator.buildFooter(safePage, "/sg audit {n}", false, lang));
        }).exceptionally(ex -> {
            sender.sendMessage(plugin.getLocaleManager().parseComponent("<red>Gagal mengambil catatan audit: " + ex.getMessage()));
            return null;
        });
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("verify", "1", "2", "3").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
