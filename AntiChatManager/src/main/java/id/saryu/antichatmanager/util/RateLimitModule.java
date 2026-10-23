package id.saryu.antichatmanager.util;

import id.saryu.antichatmanager.AntiChatManager;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player sliding-window rate limiter for commands, chat and tab-completes.
 * Configured in {@code security.rate-limit}. Thread-safe: packet handlers call
 * this from netty threads while Bukkit events call it from the main thread.
 */
public class RateLimitModule {

    public enum Kind { COMMAND, CHAT, TAB }

    private final AntiChatManager plugin;
    private final Map<UUID, EnumMap<Kind, Deque<Long>>> buckets = new ConcurrentHashMap<>();
    private boolean enabled;
    private int commandMax;
    private int chatMax;
    private int tabMax;
    private String action;

    public RateLimitModule(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        enabled = plugin.getConfigModule().getBoolean("security.rate-limit.enabled", true);
        commandMax = plugin.getConfigModule().getInt("security.rate-limit.commands-per-minute", 60);
        chatMax = plugin.getConfigModule().getInt("security.rate-limit.chat-per-minute", 20);
        tabMax = plugin.getConfigModule().getInt("security.rate-limit.tab-completes-per-minute", 120);
        action = plugin.getConfigModule().getString("security.rate-limit.action", "cancel");
    }

    /**
     * @return true when the action is allowed, false when it should be cancelled.
     */
    public boolean check(@NotNull Player player, @NotNull Kind kind) {
        if (!enabled) return true;
        int max = switch (kind) {
            case COMMAND -> commandMax;
            case CHAT -> chatMax;
            case TAB -> tabMax;
        };
        if (max <= 0) return true;

        long now = System.currentTimeMillis();
        long cutoff = now - 60_000L;
        Deque<Long> timestamps = buckets
                .computeIfAbsent(player.getUniqueId(), id -> new EnumMap<>(Kind.class))
                .computeIfAbsent(kind, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= max) {
                onExceeded(player, kind);
                return false;
            }
            timestamps.addLast(now);
        }
        return true;
    }

    private void onExceeded(@NotNull Player player, @NotNull Kind kind) {
        String message = kind.name().toLowerCase() + " rate limit exceeded (" + player.getName() + ")";
        if ("kick".equalsIgnoreCase(action)) {
            plugin.getLib().getSchedulerModule().runSync(() -> {
                if (!player.isOnline()) return;
                Component kick = plugin.getMsg().parse(plugin.getLang().message(
                        "security.rate-limit.kick",
                        "&cToo many requests. Please slow down."));
                player.kick(kick);
            });
            plugin.getLogger().warning("Kicked " + message);
        } else if ("log-only".equalsIgnoreCase(action)) {
            plugin.getLogger().warning(message);
        } else {
            plugin.getLogger().fine(message);
        }
    }

    public void clear(@NotNull UUID uuid) {
        buckets.remove(uuid);
    }

    public boolean isEnabled() {
        return enabled;
    }
}
