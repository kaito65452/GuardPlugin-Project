package id.saryu.libcore.command;

import id.saryu.libcore.SaryuLib;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight command framework. A consuming plugin creates a {@link Root}
 * (for example {@code "acm"}), registers subcommands, and binds the root. If the
 * plugin declares the command in {@code plugin.yml} the existing {@link PluginCommand}
 * is reused (so aliases and permissions keep working); otherwise the root is
 * registered directly into the server {@link CommandMap}.
 */
public class CommandFramework {

    public interface SubCommand {
        @NotNull String name();

        /** Empty string means "no permission required". */
        @NotNull String permission();

        String description();

        String usage();

        boolean playerOnly();

        int cooldownSeconds();

        void execute(CommandSender sender, Command cmd, String label, String[] args);

        default List<String> tabComplete(CommandSender sender, String[] args) {
            return List.of();
        }
    }

    private final SaryuLib plugin;
    private final Map<String, Root> roots = new LinkedHashMap<>();

    public CommandFramework(@NotNull SaryuLib plugin) {
        this.plugin = plugin;
    }

    /**
     * Get (or create) a command root. The name is what players type after '/'.
     */
    public @NotNull Root root(@NotNull String name, @NotNull String permission,
                              @NotNull String description) {
        String key = name.toLowerCase(Locale.ROOT);
        Root existing = roots.get(key);
        if (existing != null) return existing;
        Root created = new Root(key, permission, description);
        roots.put(key, created);
        return created;
    }

    public @Nullable Root getRoot(@NotNull String name) {
        return roots.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * One root command ("/acm", "/saryulib", ...) with its subcommands.
     */
    public final class Root implements CommandExecutor, TabCompleter {

        private final String name;
        private final String permission;
        private final String description;
        private final Map<String, SubCommand> subCommands = new LinkedHashMap<>();
        private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

        private Root(@NotNull String name, @NotNull String permission, @NotNull String description) {
            this.name = name;
            this.permission = permission;
            this.description = description;
        }

        public @NotNull Root register(@NotNull SubCommand sub) {
            subCommands.put(sub.name().toLowerCase(Locale.ROOT), sub);
            return this;
        }

        public @NotNull Map<String, SubCommand> getSubCommands() {
            return subCommands;
        }

        public @NotNull String getName() {
            return name;
        }

        /**
         * Bind this root to a plugin. Uses the plugin.yml command when present.
         */
        public void bind(@NotNull JavaPlugin owner) {
            PluginCommand existing = owner.getCommand(name);
            if (existing != null) {
                existing.setExecutor(this);
                existing.setTabCompleter(this);
                if (existing.getDescription() == null || existing.getDescription().isBlank()) {
                    existing.setDescription(description);
                }
                plugin.getLogger().info("Registered command /" + name + " (plugin.yml).");
                return;
            }
            CommandMap map = plugin.getServer().getCommandMap();
            Command cmd = new Command(name) {
                @Override
                public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                    return Root.this.onCommand(sender, this, commandLabel, args);
                }

                @Override
                public List<String> tabComplete(CommandSender sender, String commandLabel, String[] args) {
                    return Root.this.onTabComplete(sender, this, commandLabel, args);
                }
            };
            cmd.setDescription(description);
            if (!permission.isEmpty()) cmd.setPermission(permission);
            map.register(name, owner.getName(), cmd);
            plugin.getLogger().info("Registered command /" + name + " (dynamic).");
        }

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!permission.isEmpty() && !sender.hasPermission(permission)) {
                sender.sendMessage(Text.msg("&cYou do not have permission to use this command."));
                return true;
            }
            if (args.length == 0) {
                sendUsage(sender, label);
                return true;
            }
            String subName = args[0].toLowerCase(Locale.ROOT);
            SubCommand sub = subCommands.get(subName);
            if (sub == null) {
                sender.sendMessage(Text.msg("&cUnknown subcommand: &7" + subName));
                sendUsage(sender, label);
                return true;
            }
            if (!sub.permission().isEmpty() && !sender.hasPermission(sub.permission())) {
                sender.sendMessage(Text.msg("&cYou do not have permission to use this subcommand."));
                return true;
            }
            if (sub.playerOnly() && !(sender instanceof Player)) {
                sender.sendMessage(Text.msg("&cThis command can only be used by a player."));
                return true;
            }
            if (sub.cooldownSeconds() > 0 && sender instanceof Player) {
                String key = sender.getName() + ":" + subName;
                Long last = cooldowns.get(key);
                long now = System.currentTimeMillis() / 1000L;
                if (last != null && now - last < sub.cooldownSeconds()) {
                    sender.sendMessage(Text.msg("&cPlease wait before using this command again."));
                    return true;
                }
                cooldowns.put(key, now);
            }
            try {
                sub.execute(sender, cmd, label, Arrays.copyOfRange(args, 1, args.length));
            } catch (Throwable t) {
                plugin.getLogger().warning("Command /" + label + " " + subName + " failed: " + t);
                sender.sendMessage(Text.msg("&cAn internal error occurred. Check the console."));
            }
            return true;
        }

        @Override
        public @Nullable List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
            if (!permission.isEmpty() && !sender.hasPermission(permission)) {
                return List.of();
            }
            if (args.length == 1) {
                List<String> out = new ArrayList<>();
                String prefix = args[0].toLowerCase(Locale.ROOT);
                for (Map.Entry<String, SubCommand> entry : subCommands.entrySet()) {
                    SubCommand sc = entry.getValue();
                    if (!entry.getKey().startsWith(prefix)) continue;
                    if (!sc.permission().isEmpty() && !sender.hasPermission(sc.permission())) continue;
                    out.add(entry.getKey());
                }
                return out;
            }
            SubCommand sc = subCommands.get(args[0].toLowerCase(Locale.ROOT));
            if (sc == null) return List.of();
            if (!sc.permission().isEmpty() && !sender.hasPermission(sc.permission())) return List.of();
            return sc.tabComplete(sender, Arrays.copyOfRange(args, 1, args.length));
        }

        private void sendUsage(CommandSender sender, String label) {
            sender.sendMessage(Text.msg("&7Usage: &f/" + label + " <subcommand>"));
            sender.sendMessage(Text.msg("&7Subcommands: &f" + String.join(", ", subCommands.keySet())));
        }
    }

    /**
     * Tiny message helper used by the command framework (keeps framework independent
     * of MessageModule construction order).
     */
    private static final class Text {
        private Text() {}

        static net.kyori.adventure.text.Component msg(String legacy) {
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().deserialize(legacy);
        }
    }
}
