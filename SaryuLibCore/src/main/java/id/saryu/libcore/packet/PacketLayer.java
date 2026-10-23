package id.saryu.libcore.packet;

import id.saryu.libcore.SaryuLib;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Packet layer abstraction. Primary implementation wraps PacketEvents; a no-op
 * fallback is used when PacketEvents is not present so the plugin never crashes.
 */
public class PacketLayer {

    private final SaryuLib plugin;
    private final PacketBackend backend;
    private final String backendName;

    public PacketLayer(@NotNull SaryuLib plugin) {
        this.plugin = plugin;
        PacketBackend loaded;
        String name;
        if (plugin.getServer().getPluginManager().getPlugin("PacketEvents") == null) {
            plugin.getLogger().info("PacketEvents plugin not installed; packet layer running in no-op mode "
                    + "(Bukkit command events still protect the server).");
            loaded = new NoopBackend();
            name = "no-op";
        } else {
            try {
                loaded = new PacketEventsBackend(plugin);
                name = "PacketEvents";
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "PacketEvents is installed but could not be hooked; falling back to no-op mode", t);
                loaded = new NoopBackend();
                name = "no-op";
            }
        }
        this.backend = loaded;
        this.backendName = name;
    }

    public boolean isAvailable() {
        return backend.isAvailable();
    }

    public @NotNull String getBackendName() {
        return backendName;
    }

    public boolean isPlayerBedrock(@NotNull Player player) {
        return backend.isPlayerBedrock(player);
    }

    public @Nullable String getPlayerAddress(@NotNull Player player) {
        return backend.getPlayerAddress(player);
    }

    public void registerCommandInterceptor(@NotNull CommandInterceptor interceptor) {
        backend.registerCommandInterceptor(interceptor);
    }

    public void unregisterCommandInterceptor(@NotNull CommandInterceptor interceptor) {
        backend.unregisterCommandInterceptor(interceptor);
    }

    public void sendTabListFilter(@NotNull Player player, @NotNull java.util.Set<String> hidden) {
        backend.sendTabListFilter(player, hidden);
    }

    /** Called from SaryuLibCore#onDisable. */
    public void shutdown() {
        backend.close();
    }

    public interface CommandInterceptor {
        /**
         * Return true to cancel the packet.
         */
        boolean onCommand(Player player, String command);
    }

    public interface PacketBackend {
        boolean isAvailable();

        boolean isPlayerBedrock(Player player);

        @Nullable String getPlayerAddress(Player player);

        void registerCommandInterceptor(CommandInterceptor interceptor);

        void unregisterCommandInterceptor(CommandInterceptor interceptor);

        void sendTabListFilter(Player player, java.util.Set<String> hidden);

        default void close() {
        }
    }
}
