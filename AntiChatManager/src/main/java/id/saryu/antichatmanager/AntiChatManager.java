package id.saryu.antichatmanager;

import id.saryu.antichatmanager.alert.AlertManager;
import id.saryu.antichatmanager.audit.PermissionAudit;
import id.saryu.antichatmanager.blacklist.BlacklistManager;
import id.saryu.antichatmanager.command.ACMCommand;
import id.saryu.antichatmanager.command.CommandHandler;
import id.saryu.antichatmanager.conflict.ConflictDetector;
import id.saryu.antichatmanager.identity.PluginIdentityService;
import id.saryu.antichatmanager.integration.PluginIntegrationService;
import id.saryu.antichatmanager.listener.PlayerListener;
import id.saryu.antichatmanager.packet.PacketInterceptor;
import id.saryu.antichatmanager.punishment.PunishmentTracker;
import id.saryu.antichatmanager.scan.AutoScanner;
import id.saryu.antichatmanager.security.AuditLog;
import id.saryu.antichatmanager.security.SecurityAudit;
import id.saryu.antichatmanager.storage.Storage;
import id.saryu.antichatmanager.util.RateLimitModule;
import id.saryu.libcore.BuildInfo;
import id.saryu.libcore.SaryuLib;
import id.saryu.libcore.config.ConfigModule;
import id.saryu.libcore.language.LanguageManager;
import id.saryu.libcore.message.MessageModule;
import id.saryu.libcore.packet.PacketLayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * AntiChatManager entry point.
 *
 * <p>The plugin owns its own {@code config.yml} and {@code languages/} folder
 * (created on first start) while reusing SaryuLibCore for the database pool,
 * message parsing, command framework, permissions and packet layer.</p>
 */
public class AntiChatManager extends JavaPlugin {

    public static final String PERM_ADMIN = "saryuantichatmanager.admin";
    public static final String PERM_BYPASS = "saryuantichatmanager.bypass";
    public static final String PERM_ALERTS = "saryuantichatmanager.alerts";

    private static AntiChatManager instance;

    private SaryuLib lib;
    private BuildInfo buildInfo;
    private ConfigModule config;
    private LanguageManager lang;
    private MessageModule msg;
    private Storage storage;
    private BlacklistManager blacklist;
    private PunishmentTracker punishmentTracker;
    private AlertManager alertManager;
    private AutoScanner autoScanner;
    private PluginIdentityService pluginIdentityService;
    private ConflictDetector conflictDetector;
    private PermissionAudit permissionAudit;
    private PluginIntegrationService pluginIntegrationService;
    private SecurityAudit securityAudit;
    private AuditLog auditLog;
    private PacketInterceptor packetInterceptor;
    private PlayerListener playerListener;
    private ACMCommand acmCommand;
    private CommandHandler commandHandler;
    private RateLimitModule rateLimit;
    private final java.util.concurrent.atomic.AtomicBoolean luckPermsRescanQueued =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public void onEnable() {
        instance = this;
        buildInfo = BuildInfo.load(AntiChatManager.class, getDescription().getVersion());

        lib = SaryuLib.getInstanceSafe();
        if (lib == null) {
            getLogger().severe("SaryuLibCore is not loaded! AntiChatManager cannot enable.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Own config + language files: plugins/AntiChatManager/config.yml,
        // plugins/AntiChatManager/languages/{eng_US,id_ID}.yml
        saveDefaultConfig();
        config = new ConfigModule(this, "config.yml");
        lang = new LanguageManager(this, config);
        msg = lib.getMessageModule();
        rateLimit = new RateLimitModule(this);

        // Make this plugin's database settings authoritative for the shared pool.
        try {
            lib.getDatabaseModule().reconfigure(config);
        } catch (Throwable t) {
            getLogger().warning("Database reconfigure failed: " + t.getMessage());
        }

        storage = new Storage(this);
        storage.init();
        storage.purgeOldData(config.getInt("database.retention-days", 30))
                .thenAccept(removed -> {
                    if (removed > 0) {
                        getLogger().info("Retention purge removed " + removed + " old row(s).");
                    }
                });

        blacklist = new BlacklistManager(this);
        punishmentTracker = new PunishmentTracker(this);
        alertManager = new AlertManager(this);
        packetInterceptor = new PacketInterceptor(this);
        playerListener = new PlayerListener(this);
        commandHandler = new CommandHandler(this);
        pluginIdentityService = new PluginIdentityService(this);
        conflictDetector = new ConflictDetector(this);
        permissionAudit = new PermissionAudit(this);
        pluginIntegrationService = new PluginIntegrationService(this);
        securityAudit = new SecurityAudit(this);
        auditLog = new AuditLog(this);
        securityAudit.runStartupAudit();

        // Packet-layer interception (only active when PacketEvents is installed).
        PacketLayer packetLayer = lib.getPacketLayer();
        packetLayer.registerCommandInterceptor(packetInterceptor);

        // /acm via SaryuLibCore's command framework (declared in plugin.yml).
        acmCommand = new ACMCommand(this);
        acmCommand.register(lib.getCommandFramework());

        // Auto-scan runs one tick after enable, never blocking startup.
        autoScanner = new AutoScanner(this);
        autoScanner.start();

        pluginIdentityService.start();
        conflictDetector.start();
        permissionAudit.start();
        pluginIntegrationService.start();
        registerLuckPermsListener();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new id.saryu.antichatmanager.integration.ACMPlaceholderExpansion(this).register();
            } catch (Throwable t) {
                getLogger().warning("PlaceholderAPI expansion registration failed: " + t.getMessage());
            }
        }

        Bukkit.getPluginManager().registerEvents(playerListener, this);
        Bukkit.getPluginManager().registerEvents(pluginIntegrationService, this);

        getLogger().info("AntiChatManager " + buildInfo.display() + " enabled. "
                + "Database=" + lib.getDatabaseModule().getType()
                + ", packet layer=" + packetLayer.getBackendName()
                + ", blacklist entries=" + blacklist.count(null));
    }

    @Override
    public void onDisable() {
        if (autoScanner != null) autoScanner.stop();
        if (pluginIdentityService != null) pluginIdentityService.stop();
        if (conflictDetector != null) conflictDetector.stop();
        if (permissionAudit != null) permissionAudit.stop();
        if (pluginIntegrationService != null) pluginIntegrationService.stop();
        if (lib != null) lib.getPermissionModule().unregisterChangeListeners();
        if (packetInterceptor != null && lib != null) {
            lib.getPacketLayer().unregisterCommandInterceptor(packetInterceptor);
        }
        if (config != null) config.save();
        if (storage != null) storage.shutdown();
        instance = null;
        getLogger().info("AntiChatManager disabled.");
    }

    public static AntiChatManager getInstance() {
        AntiChatManager current = instance;
        if (current == null) throw new IllegalStateException("AntiChatManager not enabled.");
        return current;
    }

    public static AntiChatManager getInstanceSafe() {
        return instance;
    }

    public SaryuLib getLib() { return lib; }
    public BuildInfo getBuildInfo() { return buildInfo; }
    public ConfigModule getConfigModule() { return config; }
    public LanguageManager getLang() { return lang; }
    public MessageModule getMsg() { return msg; }
    public Storage getStorage() { return storage; }
    public BlacklistManager getBlacklist() { return blacklist; }
    public PunishmentTracker getPunishmentTracker() { return punishmentTracker; }
    public AlertManager getAlertManager() { return alertManager; }
    public AutoScanner getAutoScanner() { return autoScanner; }
    public PluginIdentityService getPluginIdentityService() { return pluginIdentityService; }
    public ConflictDetector getConflictDetector() { return conflictDetector; }
    public PermissionAudit getPermissionAudit() { return permissionAudit; }
    public PluginIntegrationService getPluginIntegrationService() { return pluginIntegrationService; }
    public SecurityAudit getSecurityAudit() { return securityAudit; }
    public AuditLog getAuditLog() { return auditLog; }
    public id.saryu.antichatmanager.api.AntiChatManagerAPI getApi() {
        return new id.saryu.antichatmanager.api.AntiChatManagerAPI(this);
    }

    private void registerLuckPermsListener() {
        int count = lib.getPermissionModule().registerChangeListener(this::queueLuckPermsRescan);
        if (count > 0) {
            getLogger().info("LuckPerms mutations will trigger a debounced conflict/audit rescan.");
        }
    }

    private void queueLuckPermsRescan() {
        if (!luckPermsRescanQueued.compareAndSet(false, true)) return;
        lib.getSchedulerModule().runSyncLater(() -> {
            luckPermsRescanQueued.set(false);
            try {
                if (conflictDetector != null) conflictDetector.scan();
                if (permissionAudit != null) permissionAudit.scan();
            } catch (Throwable t) {
                getLogger().log(java.util.logging.Level.FINE,
                        "LuckPerms-triggered rescan failed: " + t.getMessage(), t);
            }
        }, 100L);
    }

    /** Reload every runtime component; used by /acm reload and the public API. */
    public void reloadAll() {
        config.reload();
        lang.reload();
        lib.getDatabaseModule().reconfigure(config);
        rateLimit.reloadConfig();
        alertManager.reloadConfig();
        punishmentTracker.reloadConfig();
        autoScanner.reloadConfig();
        pluginIdentityService.reloadConfig();
        conflictDetector.reloadConfig();
        permissionAudit.reloadConfig();
        pluginIntegrationService.reloadConfig();
        pluginIntegrationService.scanAsync(null);
    }
    public PacketInterceptor getPacketInterceptor() { return packetInterceptor; }
    public PlayerListener getPlayerListener() { return playerListener; }
    public ACMCommand getAcmCommand() { return acmCommand; }
    public CommandHandler getCommandHandler() { return commandHandler; }
    public RateLimitModule getRateLimit() { return rateLimit; }

    /** Effective max command length, preferring the security section. */
    public int maxCommandLength() {
        int value = config.getInt("security.max-command-length",
                config.getInt("blacklist.max-command-length", 256));
        return value > 0 ? value : 256;
    }

    public boolean canBypass(@NotNull Player player) {
        if (player.hasPermission(PERM_BYPASS)) return true;
        boolean allowOp = config.getBoolean("blacklist.bypass.allow-op", true);
        return allowOp && player.isOp();
    }

    public boolean isAdmin(@NotNull Player player) {
        return player.hasPermission(PERM_ADMIN);
    }
}
