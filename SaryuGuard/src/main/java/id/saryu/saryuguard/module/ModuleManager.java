package id.saryu.saryuguard.module;

import id.saryu.saryuguard.SaryuGuardPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.logging.Level;

/**
 * Manages the lifecycle of all SaryuGuard modules.
 */
public class ModuleManager {

    private final SaryuGuardPlugin plugin;
    private final Map<String, SaryuGuardModule> modules = new LinkedHashMap<>();

    public ModuleManager(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void registerModule(@NotNull SaryuGuardModule module) {
        modules.put(module.id().toLowerCase(Locale.ROOT), module);
    }

    public synchronized void enableModules() {
        for (SaryuGuardModule module : modules.values()) {
            String modId = module.id().toLowerCase(Locale.ROOT);
            boolean isEnabledInConfig = isModuleConfigEnabled(modId);

            if (isEnabledInConfig) {
                try {
                    module.enable();
                    plugin.getLogger().info("[Module] " + module.displayName() + " diaktifkan: " + module.getStatus());
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.SEVERE, "Gagal mengaktifkan modul " + module.displayName() + ": " + t.getMessage(), t);
                }
            } else {
                try {
                    module.disable();
                } catch (Throwable ignored) {}
            }
        }
    }

    public synchronized void disableModules() {
        for (SaryuGuardModule module : modules.values()) {
            try {
                module.disable();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Gagal menonaktifkan modul " + module.displayName(), t);
            }
        }
    }

    public synchronized void reloadModules() {
        for (SaryuGuardModule module : modules.values()) {
            String modId = module.id().toLowerCase(Locale.ROOT);
            boolean isEnabledInConfig = isModuleConfigEnabled(modId);

            try {
                if (isEnabledInConfig) {
                    if (module.getStatus() == ModuleStatus.DISABLED) {
                        module.enable();
                    } else {
                        module.reloadConfig();
                    }
                } else {
                    if (module.getStatus() != ModuleStatus.DISABLED) {
                        module.disable();
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Gagal memuat ulang modul " + module.displayName(), t);
            }
        }
    }

    public @NotNull Collection<SaryuGuardModule> getModules() {
        return Collections.unmodifiableCollection(modules.values());
    }

    public @Nullable SaryuGuardModule getModule(@NotNull String id) {
        return modules.get(id.toLowerCase(Locale.ROOT));
    }

    private boolean isModuleConfigEnabled(@NotNull String modId) {
        var cfg = plugin.getConfigManager().getConfig();
        // Support both <module-id>.enabled and enabled-modules.<module-id>
        if (cfg.contains(modId + ".enabled")) {
            return cfg.getBoolean(modId + ".enabled", true);
        }
        if (cfg.contains("enabled-modules." + modId)) {
            return cfg.getBoolean("enabled-modules." + modId, true);
        }
        return true;
    }
}
