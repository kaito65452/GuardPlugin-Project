package id.saryu.saryuguard.command;

import id.saryu.libcore.command.CommandFramework;
import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.crypt.CryptService;
import id.saryu.saryuguard.database.SaryuGuardDatabase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /sg crypt} – encryption status and console-only key rotation.
 */
public class CryptSubcommand implements CommandFramework.SubCommand {

    private final SaryuGuardPlugin plugin;

    public CryptSubcommand(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String name() { return "crypt"; }
    @Override public @NotNull String permission() { return "saryuguard.crypt"; }
    @Override public String description() { return "Status enkripsi dan rotasi kunci (rotate: console saja)"; }
    @Override public String usage() { return "/sg crypt [status|rotate]"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 0; }

    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> showStatus(sender);
            case "rotate" -> rotate(sender);
            default -> sender.sendMessage(Component.text(
                    "Penggunaan: /sg crypt [status|rotate]", NamedTextColor.YELLOW));
        }
    }

    @Override
    public @NotNull List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            for (String s : List.of("status", "rotate")) {
                if (s.startsWith(p)) out.add(s);
            }
        }
        return out;
    }

    private void showStatus(CommandSender sender) {
        CryptService crypt = plugin.getCryptService();
        SaryuGuardDatabase db = plugin.getDatabase();
        sender.sendMessage(Component.text("=== SaryuGuard Crypt ===", NamedTextColor.GOLD));
        if (crypt == null) {
            sender.sendMessage(Component.text("CryptService belum siap.", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Enkripsi: ", NamedTextColor.GRAY)
                .append(Component.text(crypt.isEnabled() ? "AKTIF" : "NONAKTIF",
                        crypt.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        sender.sendMessage(Component.text("key_version: " + crypt.getKeyVersion(), NamedTextColor.GRAY));
        if (db != null) {
            sender.sendMessage(Component.text("Database: ", NamedTextColor.GRAY)
                    .append(Component.text(db.isAvailable()
                                    ? db.getDbType() + " (siap)"
                                    : "tidak tersedia",
                            db.isAvailable() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        }
        sender.sendMessage(Component.text(
                "Rotasi: /sg crypt rotate (hanya console)", NamedTextColor.DARK_GRAY));
    }

    private void rotate(CommandSender sender) {
        if (sender instanceof Player) {
            sender.sendMessage(Component.text(
                    "[SaryuGuard] /sg crypt rotate hanya boleh dijalankan dari console.",
                    NamedTextColor.RED));
            return;
        }
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(Component.text(
                    "[SaryuGuard] Rotasi kunci ditolak: bukan console.", NamedTextColor.RED));
            return;
        }

        CryptService crypt = plugin.getCryptService();
        SaryuGuardDatabase db = plugin.getDatabase();
        if (crypt == null || db == null || !db.isAvailable()) {
            sender.sendMessage(Component.text(
                    "[SaryuGuard] Database/Crypt tidak siap; rotasi dibatalkan.", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text(
                "[SaryuGuard] Memulai rotasi kunci AES…", NamedTextColor.YELLOW));
        try {
            int rows = crypt.rotateKeys(db);
            sender.sendMessage(Component.text(
                    "[SaryuGuard] Rotasi selesai. key_version=" + crypt.getKeyVersion()
                            + " | baris di-reencrypt: " + rows
                            + " | cadangan: secret.key.bak",
                    NamedTextColor.GREEN));
        } catch (Exception e) {
            sender.sendMessage(Component.text(
                    "[SaryuGuard] Rotasi gagal: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "[Crypt] Rotasi kunci gagal: " + e.getMessage(), e);
        }
    }
}
