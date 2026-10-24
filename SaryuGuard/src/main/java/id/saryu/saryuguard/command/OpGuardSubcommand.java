package id.saryu.saryuguard.command;

import id.saryu.libcore.command.CommandFramework;
import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.module.SaryuGuardModule;
import id.saryu.saryuguard.opguard.OpGuardModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * {@code /sg opguard [info|reload]} – OpGuard module management.
 */
public class OpGuardSubcommand implements CommandFramework.SubCommand {

    private final SaryuGuardPlugin plugin;

    public OpGuardSubcommand(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String name() { return "opguard"; }
    @Override public @NotNull String permission() { return "saryuguard.admin"; }
    @Override public String description() { return "Kelola modul OpGuard"; }
    @Override public String usage() { return "/sg opguard [info|reload]"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 0; }

    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        OpGuardModule og = getOpGuard();
        if (og == null) {
            sender.sendMessage(Component.text("[SaryuGuard] Modul OpGuard tidak tersedia.", NamedTextColor.RED));
            return;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "info";
        switch (sub) {
            case "info" -> {
                sender.sendMessage(Component.text("═══ OpGuard Status ═══", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Status: " + og.getStatus(), NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("Mode: " + og.getMode(), NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("OP sah: " + og.getAllowedOps().size(), NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("Detail: " + og.getStatusMessage(), NamedTextColor.GRAY));
            }
            case "reload" -> {
                og.disable();
                try {
                    og.enable();
                    sender.sendMessage(Component.text("[OpGuard] Reload berhasil.", NamedTextColor.GREEN));
                } catch (Exception e) {
                    sender.sendMessage(Component.text("[OpGuard] Reload gagal: " + e.getMessage(), NamedTextColor.RED));
                }
            }
            default -> sender.sendMessage(Component.text(
                    "Penggunaan: /sg opguard [info|reload]", NamedTextColor.YELLOW));
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return List.of("info", "reload");
        return List.of();
    }

    private OpGuardModule getOpGuard() {
        SaryuGuardModule m = plugin.getModule("opguard");
        return m instanceof OpGuardModule ? (OpGuardModule) m : null;
    }
}
