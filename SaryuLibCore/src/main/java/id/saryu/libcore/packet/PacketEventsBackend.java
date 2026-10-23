package id.saryu.libcore.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommandUnsigned;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTabComplete;
import id.saryu.libcore.SaryuLib;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PacketEvents 2.x backed packet layer.
 *
 * <p>PacketEvents is a {@code softdepend}: this class is only loaded when the
 * plugin is installed, so no PacketEvents classes are required at runtime
 * otherwise. Command packets are inspected before Bukkit dispatches them, and
 * tab-complete responses are filtered for players without bypass permission.</p>
 */
class PacketEventsBackend implements PacketLayer.PacketBackend, PacketListener {

    private final SaryuLib plugin;
    private final Set<PacketLayer.CommandInterceptor> interceptors = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<String>> tabHidden = new ConcurrentHashMap<>();

    private volatile PacketListenerCommon registered;
    private int attempts;

    PacketEventsBackend(@NotNull SaryuLib plugin) {
        this.plugin = plugin;
        register();
    }

    private void register() {
        if (registered != null) return;
        try {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            if (api == null) {
                retry("PacketEvents API not initialised yet");
                return;
            }
            registered = api.getEventManager().registerListener(this, PacketListenerPriority.NORMAL);
            plugin.getLogger().info("PacketEvents hooked (version " + api.getVersion()
                    + "); packet-level command filtering active.");
        } catch (Throwable t) {
            retry("listener registration failed: " + t);
        }
    }

    private void retry(String reason) {
        attempts++;
        if (attempts <= 5) {
            plugin.getLogger().info("PacketEvents hook attempt " + attempts + " failed (" + reason
                    + "); retrying in 2s.");
            plugin.getSchedulerModule().runSyncLater(this::register, 40L);
        } else {
            plugin.getLogger().warning("PacketEvents could not be hooked after " + attempts
                    + " attempts (" + reason + "). Bukkit-level protection stays active.");
        }
    }

    @Override
    public boolean isAvailable() {
        return registered != null;
    }

    @Override
    public boolean isPlayerBedrock(@NotNull Player player) {
        return plugin.getIntegrationModule().isBedrock(player);
    }

    @Override
    public @Nullable String getPlayerAddress(@NotNull Player player) {
        try {
            java.net.InetSocketAddress addr = player.getAddress();
            return addr != null && addr.getAddress() != null ? addr.getAddress().getHostAddress() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void registerCommandInterceptor(PacketLayer.CommandInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    @Override
    public void unregisterCommandInterceptor(PacketLayer.CommandInterceptor interceptor) {
        interceptors.remove(interceptor);
    }

    @Override
    public void sendTabListFilter(@NotNull Player player, @NotNull Set<String> hidden) {
        tabHidden.put(player.getUniqueId(), new HashSet<>(hidden));
    }

    @Override
    public void close() {
        try {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            PacketListenerCommon listener = registered;
            if (api != null && listener != null) {
                api.getEventManager().unregisterListener(listener);
            }
        } catch (Throwable ignored) {
        }
        registered = null;
        interceptors.clear();
        tabHidden.clear();
    }

    /* ------------------------------------------------------------------ */
    /* PacketEvents callbacks (netty threads)                             */
    /* ------------------------------------------------------------------ */

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // Never let an exception escape into PacketEvents/Netty: an uncaught
        // throwable aborts the pipeline and disconnects the player.
        try {
            String command = extractCommand(event);
            if (command == null || command.isBlank()) return;
            Player player = resolvePlayer(event.getUser(), event.getPlayer());
            if (player == null) return;
            for (PacketLayer.CommandInterceptor interceptor : interceptors) {
                try {
                    if (interceptor.onCommand(player, command)) {
                        event.setCancelled(true);
                        return;
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("Command interceptor failed: " + t);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Packet receive handling failed: " + t, t);
        }
    }

    private @Nullable String extractCommand(PacketReceiveEvent event) {
        try {
            if (event.getPacketType() == PacketType.Play.Client.CHAT_COMMAND) {
                return new WrapperPlayClientChatCommand(event).getCommand();
            }
            if (event.getPacketType() == PacketType.Play.Client.CHAT_COMMAND_UNSIGNED) {
                return new WrapperPlayClientChatCommandUnsigned(event).getCommand();
            }
            if (event.getPacketType() == PacketType.Play.Client.CHAT_MESSAGE) {
                String message = new WrapperPlayClientChatMessage(event).getMessage();
                if (message != null && message.startsWith("/")) {
                    return message.substring(1);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to read command packet: " + t);
        }
        return null;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        try {
            if (event.getPacketType() != PacketType.Play.Server.TAB_COMPLETE) return;
            Player player = resolvePlayer(event.getUser(), event.getPlayer());
            if (player == null) return;
            Set<String> hidden = tabHidden.get(player.getUniqueId());
            if (hidden == null || hidden.isEmpty()) return;
            WrapperPlayServerTabComplete wrapper = new WrapperPlayServerTabComplete(event);
            List<WrapperPlayServerTabComplete.CommandMatch> matches = wrapper.getCommandMatches();
            if (matches == null || matches.isEmpty()) return;
            List<WrapperPlayServerTabComplete.CommandMatch> filtered = new ArrayList<>(matches.size());
            for (WrapperPlayServerTabComplete.CommandMatch match : matches) {
                String text = match.getText();
                String base = text == null ? "" : text.split(" ", 2)[0].toLowerCase();
                if (hidden.contains(base)) continue;
                filtered.add(match);
            }
            if (filtered.size() != matches.size()) {
                wrapper.setCommandMatches(filtered);
                wrapper.write();
            }
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.FINE, "Tab-complete filter failed: " + t, t);
        }
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        try {
            User user = event.getUser();
            // Connections that drop before login have no UUID yet. Passing null
            // to ConcurrentHashMap#remove throws NPE inside the Netty thread.
            UUID uuid = user != null ? user.getUUID() : null;
            if (uuid != null) {
                tabHidden.remove(uuid);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.FINE, "Disconnect cleanup failed: " + t, t);
        }
    }

    private @Nullable Player resolvePlayer(@Nullable User user, @Nullable Object eventPlayer) {
        try {
            if (eventPlayer instanceof Player player) return player;
            if (user != null) {
                UUID uuid = user.getUUID();
                if (uuid != null) return Bukkit.getPlayer(uuid);
            }
        } catch (Throwable ignored) {
            // Early/handshake connections may not expose a player yet.
        }
        return null;
    }
}
