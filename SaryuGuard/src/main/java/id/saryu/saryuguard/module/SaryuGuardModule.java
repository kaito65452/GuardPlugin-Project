package id.saryu.saryuguard.module;

import org.jetbrains.annotations.NotNull;

/**
 * Lifecycle contract for SaryuGuard components.
 * Real status reporting: ACTIVE, DEGRADED, DISABLED, ERROR.
 */
public interface SaryuGuardModule {

    @NotNull String id();

    default @NotNull String getName() {
        return id();
    }

    @NotNull String displayName();

    default boolean isEnabled() {
        return getStatus() == ModuleStatus.ACTIVE || getStatus() == ModuleStatus.DEGRADED;
    }

    @NotNull ModuleStatus getStatus();

    default @NotNull String getStatusMessage() {
        return "";
    }

    void enable() throws Exception;

    void disable();

    default void reloadConfig() {}
}
