package id.saryu.libcore.scheduler;

import id.saryu.libcore.SaryuLib;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import java.util.concurrent.CompletableFuture;

/**
 * Paper/Folia-aware scheduler abstraction. Provides sync, async, region
 * (Folia), delayed and repeating task helpers without blocking the main thread.
 */
public class SchedulerModule {

    private final SaryuLib plugin;
    private final boolean folia;

    public SchedulerModule(@NotNull SaryuLib plugin) {
        this.plugin = plugin;
        this.folia = checkFolia();
    }

    private boolean checkFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public boolean isFolia() {
        return folia;
    }

    public @NotNull BukkitTask runSync(@NotNull Runnable r) {
        return Bukkit.getScheduler().runTask(plugin, r);
    }

    public @NotNull BukkitTask runAsync(@NotNull Runnable r) {
        return Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
    }

    public @NotNull BukkitTask runSyncLater(@NotNull Runnable r, long ticks) {
        return Bukkit.getScheduler().runTaskLater(plugin, r, ticks);
    }

    public @NotNull BukkitTask runAsyncLater(@NotNull Runnable r, long ticks) {
        return Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, r, ticks);
    }

    public @NotNull BukkitTask runSyncTimer(@NotNull Runnable r, long delay, long period) {
        return Bukkit.getScheduler().runTaskTimer(plugin, r, delay, period);
    }

    public @NotNull BukkitTask runAsyncTimer(@NotNull Runnable r, long delay, long period) {
        return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, r, delay, period);
    }

    /**
     * Folia-region aware: run on the region of the given entity. Falls back to
     * sync on non-Folia servers.
     */
    public @NotNull BukkitTask runRegion(@NotNull org.bukkit.entity.Entity entity, @NotNull Runnable r) {
        if (folia) {
            try {
                var scheduler = Bukkit.getScheduler();
                var method = scheduler.getClass().getMethod("runTaskRegion", org.bukkit.plugin.Plugin.class, org.bukkit.entity.Entity.class, Runnable.class);
                return (BukkitTask) method.invoke(scheduler, plugin, entity, r);
            } catch (Exception ignored) {
                return runSync(r);
            }
        }
        return runSync(r);
    }

    /**
     * Execute an async operation then continue on sync thread.
     */
    public @NotNull CompletableFuture<Void> asyncThenSync(@NotNull java.util.function.Supplier<Void> op,
                                                          @NotNull java.util.function.Consumer<Void> then) {
        return CompletableFuture.runAsync(() -> op.get()).thenRunAsync(() -> then.accept(null), r -> Bukkit.getScheduler().runTask(plugin, r));
    }
}
