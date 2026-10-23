package id.saryu.antichatmanager.punishment;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.libcore.time.TimeUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Tracks violations per player and applies progressive punishment steps
 * (warn -> kick -> tempban -> permban) based on config.
 *
 * <p>The in-memory counter is authoritative for escalation decisions so
 * punishments still work when the database is unavailable. Database counts are
 * persisted in the background and reconciled on restart.</p>
 */
public class PunishmentTracker {

    public enum StepAction { WARN, KICK, BAN }

    private final AntiChatManager plugin;
    private final TimeUtil timeUtil;
    private final Map<String, Integer> memoryCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastAppliedStep = new ConcurrentHashMap<>();
    private boolean enabled;
    private String countMode;
    private long countWindowMillis;
    private boolean persist;
    private boolean resetAfterBan;

    public PunishmentTracker(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
        this.timeUtil = new TimeUtil();
        reloadConfig();
    }

    public void reloadConfig() {
        enabled = plugin.getConfigModule().getBoolean("punishment.enabled", true);
        countMode = plugin.getConfigModule().getString("punishment.count-mode", "global");
        String window = plugin.getConfigModule().getString("punishment.count-window", "60m");
        countWindowMillis = "0".equals(window) ? -1 : parseDurationMs(window);
        persist = plugin.getConfigModule().getBoolean("punishment.persist", true);
        resetAfterBan = plugin.getConfigModule().getBoolean("punishment.reset-after-ban", true);
    }

    private long parseDurationMs(String d) {
        try {
            return new id.saryu.libcore.punishment.DurationParser().parse(d).millis;
        } catch (Exception e) {
            return 3600000L;
        }
    }

    /**
     * Record a violation for a player and apply the appropriate escalation step.
     * Safe to call from any thread; punishment application is scheduled on the
     * main thread.
     */
    public void record(@NotNull Player player, @NotNull String command) {
        if (!enabled || plugin.canBypass(player)) return;

        final String uuid = player.getUniqueId().toString();
        final String name = player.getName();
        final String key = "per-command".equals(countMode) ? uuid + ":" + command : uuid;

        int memoryCount = memoryCounts.merge(key, 1, Integer::sum);

        if (persist && plugin.getStorage() != null) {
            plugin.getStorage().incrementViolation(key, name).thenAccept(dbCount -> {
                int count = Math.max(memoryCount, dbCount);
                evaluate(player, command, key, count);
            }).exceptionally(ex -> {
                plugin.getLogger().log(Level.FINE, "Violation persistence failed", ex);
                evaluate(player, command, key, memoryCount);
                return null;
            });
        } else {
            evaluate(player, command, key, memoryCount);
        }
    }

    private void evaluate(@NotNull Player player, @NotNull String command,
                          @NotNull String key, int count) {
        int threshold = thresholdFor(command, count);
        if (threshold <= 0) return;
        int last = lastAppliedStep.getOrDefault(key, 0);
        if (threshold <= last) return;
        lastAppliedStep.put(key, threshold);

        plugin.getLib().getSchedulerModule().runSync(() -> {
            if (player.isOnline()) {
                applyStep(player, command, count, threshold);
            }
        });
    }

    /**
     * Highest step threshold at or below the current count, or 0 if none.
     */
    private int thresholdFor(@NotNull String command, int count) {
        ConfigurationSection perCommand = plugin.getConfigModule().get()
                .getConfigurationSection("punishment.per-command-overrides");
        String base = plugin.getBlacklist().stripNamespace(command);
        if (perCommand != null) {
            ConfigurationSection override = perCommand.getConfigurationSection(base);
            if (override != null) {
                int v = override.getInt("violations", 0);
                if (v > 0 && count >= v) return v;
                return 0;
            }
        }

        List<Map<?, ?>> steps = plugin.getConfigModule().get().getMapList("punishment.steps");
        int best = 0;
        for (Map<?, ?> step : steps) {
            Object v = step.get("violations");
            if (v instanceof Number n) {
                int threshold = n.intValue();
                if (threshold > 0 && count >= threshold && threshold > best) {
                    best = threshold;
                }
            }
        }
        return best;
    }

    private void applyStep(@NotNull Player player, @NotNull String command, int count, int threshold) {
        // Per-command override?
        ConfigurationSection perCommand = plugin.getConfigModule().get()
                .getConfigurationSection("punishment.per-command-overrides");
        String base = plugin.getBlacklist().stripNamespace(command);
        ConfigurationSection override = perCommand != null ? perCommand.getConfigurationSection(base) : null;

        String actionStr;
        String message;
        String reason;
        String duration;
        if (override != null && override.getInt("violations", 0) == threshold) {
            actionStr = override.getString("action", "WARN");
            message = override.getString("message", "");
            reason = override.getString("reason", "");
            duration = override.getString("duration", "");
        } else {
            Map<?, ?> chosen = null;
            for (Map<?, ?> step : plugin.getConfigModule().get().getMapList("punishment.steps")) {
                Object v = step.get("violations");
                if (v instanceof Number n && n.intValue() == threshold) {
                    chosen = step;
                    break;
                }
            }
            if (chosen == null) return;
            actionStr = str(chosen, "action", "WARN");
            message = str(chosen, "message", "");
            reason = str(chosen, "reason", "");
            duration = str(chosen, "duration", "");
        }

        StepAction action;
        try {
            action = StepAction.valueOf(actionStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            action = StepAction.WARN;
        }

        int limit = threshold;
        message = replacePlaceholders(message, player, command, count, limit, duration);
        reason = replacePlaceholders(reason, player, command, count, limit, duration);

        switch (action) {
            case WARN -> {
                if (!message.isEmpty()) player.sendMessage(plugin.getMsg().parse(message));
                plugin.getLib().getPunishmentService().warn(player, reason, "AntiChatManager");
            }
            case KICK -> {
                String kickMessage = message.isEmpty() ? reason : message;
                plugin.getLib().getPunishmentService().kick(player, kickMessage, "AntiChatManager");
            }
            case BAN -> {
                if ("perm".equalsIgnoreCase(duration)) {
                    plugin.getLib().getPunishmentService().ban(player, reason, "AntiChatManager");
                } else {
                    plugin.getLib().getPunishmentService().tempban(player, duration, reason, "AntiChatManager");
                }
                if (resetAfterBan) {
                    resetPlayer(player.getUniqueId().toString());
                }
            }
        }

        plugin.getLogger().info("Punishment " + action + " applied to " + player.getName()
                + " (" + count + "/" + limit + ") for " + command);
    }

    private String str(Map<?, ?> map, String key, String def) {
        Object value = map.get(key);
        return value == null ? def : String.valueOf(value);
    }

    private @NotNull String replacePlaceholders(@NotNull String text, @NotNull Player player,
                                                @NotNull String command, int count, int limit,
                                                @NotNull String duration) {
        if (text.isEmpty()) return text;
        return text
                .replace("{PLAYER}", player.getName())
                .replace("{COUNT}", String.valueOf(count))
                .replace("{LIMIT}", String.valueOf(limit))
                .replace("{DURATION}", duration)
                .replace("{REASON}", text)
                .replace("{CMDBLACKLIST}", command)
                .replace("{WORLD}", player.getWorld().getName())
                .replace("{TIME}", timeUtil.formatNow(
                        plugin.getConfigModule().getString("timezone", "WIB")))
                .replace("{NAMESERVER}", plugin.getConfigModule().getString("alert.server-name", "Server Security"));
    }

    public void expireOldViolations() {
        if (countWindowMillis <= 0) {
            memoryCounts.clear();
            lastAppliedStep.clear();
            return;
        }
        // Memory counts are cheap; the DB layer enforces the window per player.
    }

    public void resetPlayer(@NotNull String uuid) {
        memoryCounts.remove(uuid);
        lastAppliedStep.remove(uuid);
        plugin.getStorage().resetViolationCount(uuid);
    }

    public int getMemoryCount(@NotNull String uuid) {
        return memoryCounts.getOrDefault(uuid, 0);
    }

    public boolean isEnabled() {
        return enabled;
    }
}
