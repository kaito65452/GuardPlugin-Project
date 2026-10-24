package id.saryu.saryuguard;

import id.saryu.libcore.BuildInfo;
import id.saryu.libcore.SaryuLib;
import id.saryu.saryuguard.audit.AuditModule;
import id.saryu.saryuguard.command.SaryuGuardCommand;
import id.saryu.saryuguard.compat.CompatService;
import id.saryu.saryuguard.config.ConfigManager;
import id.saryu.saryuguard.crypt.CryptService;
import id.saryu.saryuguard.database.SaryuGuardDatabase;
import id.saryu.saryuguard.filter.ChatFilterModule;
import id.saryu.saryuguard.locale.LocaleManager;
import id.saryu.saryuguard.mfa.TwoFactorModule;
import id.saryu.saryuguard.module.ModuleManager;
import id.saryu.saryuguard.module.SaryuGuardModule;
import id.saryu.saryuguard.opguard.OpGuardModule;
import id.saryu.saryuguard.connection.ConnectionGuardModule;
import id.saryu.saryuguard.scanner.MalwareScannerModule;
import id.saryu.saryuguard.verify.PluginVerifyModule;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.logging.Level;

/**
 * SaryuGuard Core Plugin.
 * Advanced server security for Paper 1.21.x - 26.x.
 */
public class SaryuGuardPlugin extends JavaPlugin {

    private static SaryuGuardPlugin instance;

    private SaryuLib lib;
    private BuildInfo buildInfo;
    private ConfigManager configManager;
    private LocaleManager localeManager;
    private SaryuGuardDatabase database;
    private CryptService cryptService;
    private CompatService compatService;
    private ModuleManager moduleManager;
    private SaryuGuardCommand commandHandler;

    @Override
    public void onEnable() {
        instance = this;
        this.buildInfo = BuildInfo.load(SaryuGuardPlugin.class, "4.0.0");

        // 1. Check SaryuLibCore dependency
        this.lib = SaryuLib.getInstanceSafe();
        if (this.lib == null) {
            getLogger().severe("SaryuLibCore tidak ditemukan! SaryuGuard membutuhkan SaryuLibCore untuk berjalan.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Load configurations and locales
        this.configManager = new ConfigManager(this);
        this.configManager.loadConfigs();

        this.localeManager = new LocaleManager(this);
        this.localeManager.loadLocales();

        // 3. Initialize Database
        this.database = new SaryuGuardDatabase(this);
        try {
            this.database.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Gagal menginisialisasi database SaryuGuard: " + e.getMessage(), e);
        }

        // 4. Initialize CryptService
        this.cryptService = new CryptService(this);
        try {
            this.cryptService.init(this.database);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Gagal menginisialisasi CryptService: " + e.getMessage(), e);
        }

        // 5. Initialize Compatibility Service (anti-cheat detection)
        this.compatService = new CompatService(this);
        try {
            this.compatService.scan();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Pemeriksaan kompatibilitas awal gagal: " + e.getMessage(), e);
        }

        // 6. Register and enable Modules
        this.moduleManager = new ModuleManager(this);
        registerBuiltinModules();
        this.moduleManager.enableModules();

        // 7. Register Commands (/sg)
        this.commandHandler = new SaryuGuardCommand(this);
        this.commandHandler.register(lib.getCommandFramework());

        getLogger().info(localeManager.getMessage("plugin.enabled", "SaryuGuard " + buildInfo.display() + " aktif."));
    }

    private void registerBuiltinModules() {
        // Phase B: Plugin Verify
        moduleManager.registerModule(new PluginVerifyModule(this));

        // Phase D1: Chat Filter
        moduleManager.registerModule(new ChatFilterModule(this));

        // Phase D2: OpGuard & Staff 2FA
        moduleManager.registerModule(new OpGuardModule(this));
        moduleManager.registerModule(new TwoFactorModule(this));

        // Phase E2: Audit & Forensics
        moduleManager.registerModule(new AuditModule(this));

        // Phase E3: Connection Guard
        moduleManager.registerModule(new ConnectionGuardModule(this));

        // Phase F: Malware Scanner (MCAntiMalware wrapper)
        moduleManager.registerModule(new MalwareScannerModule(this));
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) {
            moduleManager.disableModules();
        }
        if (cryptService != null) {
            cryptService.destroy();
        }
        if (database != null) {
            database.close();
        }
        getLogger().info("SaryuGuard dinonaktifkan.");
        instance = null;
    }

    public synchronized void reloadAll() {
        if (configManager != null) {
            configManager.loadConfigs();
        }
        if (localeManager != null) {
            localeManager.loadLocales();
        }
        if (compatService != null) {
            compatService.scan();
        }
        if (moduleManager != null) {
            moduleManager.reloadModules();
        }
    }

    public static SaryuGuardPlugin getInstance() {
        return instance;
    }

    public SaryuLib getLib() {
        return lib;
    }

    public BuildInfo getBuildInfo() {
        return buildInfo;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LocaleManager getLocaleManager() {
        return localeManager;
    }

    public SaryuGuardDatabase getDatabase() {
        return database;
    }

    public CryptService getCryptService() {
        return cryptService;
    }

    public CompatService getCompatService() {
        return compatService;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public @NotNull Collection<SaryuGuardModule> getModules() {
        return moduleManager != null ? moduleManager.getModules() : java.util.Collections.emptyList();
    }

    public @Nullable SaryuGuardModule getModule(String id) {
        return moduleManager != null ? moduleManager.getModule(id) : null;
    }
}
