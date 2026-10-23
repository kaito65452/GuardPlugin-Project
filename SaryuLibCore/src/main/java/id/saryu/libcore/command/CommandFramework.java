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
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Lightweight command framework. A consuming plugin creates a {@link Root}
 * (for example {@code "acm"}), registers subcommands, and binds the root. If the
 * plugin declares the command in {@code plugin.yml} the existing {@link PluginCommand}
 * is reused (so aliases and permissions keep working); otherwise the root is
 * registered directly into the server {@link CommandMap}.
 *
 * <p>Every framework message goes through a {@link MessageSink}. The default sink
 * renders messages with SaryuLibCore's language manager and {@code MessageModule}
 * (legacy {@code &} codes, hex colours and MiniMessage). Consuming plugins can
 * install their own sink so command errors use their own language files.</p>
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

    /**
     * All player-facing messages emitted by the framework. Implementations must
     * send already-parsed components; raw legacy codes must never reach a sender.
     */
    public interface MessageSink {
        void unknownSubcommand(@NotNull CommandSender sender, @NotNull String label,
                               @NotNull String input, @Nullable String suggestion);

        void usage(@NotNull CommandSender sender, @NotNull String label, @NotNull String usage);

        void subcommands(@NotNull CommandSender sender, @NotNull String label,
                         @NotNull Collection<String> subcommands);

        void noPermission(@NotNull CommandSender sender, boolean subcommand);

        void playerOnly(@NotNull CommandSender sender);

        void cooldown(@NotNull CommandSender sender);

        void internalError(@NotNull CommandSender sender, @NotNull String label,
                           @NotNull String subcommand, @NotNull Throwable error);
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
        private MessageSink messages = new DefaultMessageSink();

        private Root(@NotNull String name, @NotNull String permission, @NotNull String description) {
            this.name = name;
            this.permission = permission;
            this.description = description;
        }

        public @NotNull Root register(@NotNull SubCommand sub) {
            subCommands.put(sub.name().toLowerCase(Locale.ROOT), sub);
            return this;
        }

        /**
         * Install a plugin-specific message sink (language files, prefix, colours).
         */
        public @NotNull Root messages(@NotNull MessageSink sink) {
            this.messages = sink;
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
                messages.noPermission(sender, false);
                return true;
            }
            if (args.length == 0) {
                sendUsage(sender, label);
                return true;
            }
            String subName = args[0].toLowerCase(Locale.ROOT);
            SubCommand sub = subCommands.get(subName);
            if (sub == null) {
                messages.unknownSubcommand(sender, label, subName, suggest(label, args));
                sendUsage(sender, label);
                return true;
            }
            if (!sub.permission().isEmpty() && !sender.hasPermission(sub.permission())) {
                messages.noPermission(sender, true);
                return true;
            }
            if (sub.playerOnly() && !(sender instanceof Player)) {
                messages.playerOnly(sender);
                return true;
            }
            if (sub.cooldownSeconds() > 0 && sender instanceof Player) {
                String key = sender.getName() + ":" + subName;
                Long last = cooldowns.get(key);
                long now = System.currentTimeMillis() / 1000L;
                if (last != null && now - last < sub.cooldownSeconds()) {
                    messages.cooldown(sender);
                    return true;
                }
                cooldowns.put(key, now);
            }
            try {
                sub.execute(sender, cmd, label, Arrays.copyOfRange(args, 1, args.length));
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING,
                        "Command /" + label + " " + subName + " failed", t);
                messages.internalError(sender, label, subName, t);
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
            messages.usage(sender, label, "/" + label + " <subcommand>");
            messages.subcommands(sender, label, subCommands.keySet());
        }

        /**
         * Build a suggestion for unknown input. When the second token is a real
         * subcommand the likely intent is a swapped order, for example
         * {@code /acm set language id_ID} -> {@code /acm language set id_ID}.
         * Otherwise the closest registered subcommand is used.
         */
        private @Nullable String suggest(@NotNull String label, @NotNull String[] args) {
            if (subCommands.isEmpty()) return null;
            if (args.length >= 2) {
                String second = args[1].toLowerCase(Locale.ROOT);
                if (subCommands.containsKey(second)) {
                    StringBuilder out = new StringBuilder("/").append(label).append(' ').append(args[1]);
                    if (!args[0].isBlank()) out.append(' ').append(args[0]);
                    for (int i = 2; i < args.length; i++) out.append(' ').append(args[i]);
                    return out.toString();
                }
            }
            String input = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            String closest = null;
            int best = Integer.MAX_VALUE;
            for (String candidate : subCommands.keySet()) {
                int distance = levenshtein(input, candidate);
                if (distance < best) {
                    best = distance;
                    closest = candidate;
                }
            }
            return closest == null ? null : "/" + label + " " + closest;
        }
    }

    private static int levenshtein(@NotNull String a, @NotNull String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /**
     * Default sink: library language file + MessageModule. Consuming plugins should
     * override this with their own sink to use their own prefix/language files.
     */
    private final class DefaultMessageSink implements MessageSink {

        @Override
        public void unknownSubcommand(@NotNull CommandSender sender, @NotNull String label,
                                      @NotNull String input, @Nullable String suggestion) {
            send(sender, "command.unknown-subcommand", "&cUnknown subcommand: &7{sub}", "sub", input);
            if (suggestion != null && !suggestion.isBlank()) {
                send(sender, "command.did-you-mean", "&7Did you mean &f{suggestion}&7?",
                        "suggestion", suggestion);
            }
        }

        @Override
        public void usage(@NotNull CommandSender sender, @NotNull String label, @NotNull String usage) {
            send(sender, "command.usage", "&7Usage: &f{usage}", "usage", usage);
        }

        @Override
        public void subcommands(@NotNull CommandSender sender, @NotNull String label,
                                @NotNull Collection<String> subcommands) {
            send(sender, "command.subcommands", "&7Subcommands: &f{subcommands}",
                    "subcommands", String.join(", ", subcommands));
        }

        @Override
        public void noPermission(@NotNull CommandSender sender, boolean subcommand) {
            send(sender, subcommand ? "command.no-sub-permission" : "command.no-permission",
                    subcommand ? "&cYou do not have permission to use this subcommand."
                            : "&cYou do not have permission to use this command.");
        }

        @Override
        public void playerOnly(@NotNull CommandSender sender) {
            send(sender, "command.player-only", "&cThis command can only be used by a player.");
        }

        @Override
        public void cooldown(@NotNull CommandSender sender) {
            send(sender, "command.cooldown", "&cPlease wait before using this command again.");
        }

        @Override
        public void internalError(@NotNull CommandSender sender, @NotNull String label,
                                  @NotNull String subcommand, @NotNull Throwable error) {
            send(sender, "command.internal-error", "&cAn internal error occurred. Check the console.");
        }

        private void send(@NotNull CommandSender sender, @NotNull String key,
                          @NotNull String def, Object... vars) {
            String raw = plugin.getLanguageManager().message(key, def);
            for (int i = 0; i + 1 < vars.length; i += 2) {
                raw = raw.replace("{" + vars[i] + "}", String.valueOf(vars[i + 1]));
            }
            String prefix = plugin.getConfigModule().getString("prefix", "&7[&bSaryuLib&7]");
            raw = raw.contains("%prefix%") ? raw.replace("%prefix%", prefix) : prefix + " " + raw;
            sender.sendMessage(plugin.getMessageModule().parse(raw));
        }
    }
}
