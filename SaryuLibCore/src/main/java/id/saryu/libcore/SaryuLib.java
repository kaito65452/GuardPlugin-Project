package id.saryu.libcore;

import id.saryu.libcore.config.ConfigModule;
import id.saryu.libcore.database.DatabaseModule;
import id.saryu.libcore.language.LanguageManager;
import id.saryu.libcore.message.MessageModule;
import id.saryu.libcore.packet.PacketLayer;
import id.saryu.libcore.command.CommandFramework;
import id.saryu.libcore.integration.IntegrationModule;
import id.saryu.libcore.permission.PermissionModule;
import id.saryu.libcore.punishment.PunishmentService;
import id.saryu.libcore.scheduler.SchedulerModule;
import id.saryu.libcore.service.ServiceRegistry;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * SaryuLibCore - shared core library for Saryu plugins.
 * <p>
 * Provides config, language, message/components, command framework, database,
 * scheduler, packet layer, permission/LuckPerms helpers, punishment service,
 * time utils, integrations, and a service registry shared by all Saryu plugins.
 */
public class SaryuLib extends JavaPlugin {

    private static SaryuLib instance;

    private final ServiceRegistry serviceRegistry = new ServiceRegistry();
    private ConfigModule configModule;
    private LanguageManager languageManager;
    private MessageModule messageModule;
    private DatabaseModule databaseModule;
    private SchedulerModule schedulerModule;
    private PacketLayer packetLayer;
    private PermissionModule permissionModule;
    private PunishmentService punishmentService;
    private IntegrationModule integrationModule;
    private CommandFramework commandFramework;

    @Override
    public void onEnable() {
        instance = this;

        configModule = new ConfigModule(this);
        languageManager = new LanguageManager(this);
        messageModule = new MessageModule(this);
        schedulerModule = new SchedulerModule(this);
        databaseModule = new DatabaseModule(this);
        packetLayer = new PacketLayer(this);
        permissionModule = new PermissionModule(this);
        punishmentService = new PunishmentService(this);
        integrationModule = new IntegrationModule(this);
        commandFramework = new CommandFramework(this);
        registerLibCommands();

        // Register core services so consuming plugins can look them up.
        registerService(ConfigModule.class, configModule);
        registerService(LanguageManager.class, languageManager);
        registerService(MessageModule.class, messageModule);
        registerService(DatabaseModule.class, databaseModule);
        registerService(SchedulerModule.class, schedulerModule);
        registerService(PacketLayer.class, packetLayer);
        registerService(PermissionModule.class, permissionModule);
        registerService(PunishmentService.class, punishmentService);
        registerService(IntegrationModule.class, integrationModule);
        registerService(CommandFramework.class, commandFramework);

        getLogger().info("SaryuLibCore "
                + BuildInfo.load(SaryuLib.class, getDescription().getVersion()).display()
                + " enabled.");
    }

    @Override
    public void onDisable() {
        if (packetLayer != null) {
            packetLayer.shutdown();
        }
        punishmentService = null;
        permissionModule = null;
        integrationModule = null;
        commandFramework = null;
        packetLayer = null;
        schedulerModule = null;
        if (databaseModule != null) {
            databaseModule.shutdown();
            databaseModule = null;
        }
        messageModule = null;
        languageManager = null;
        configModule = null;
        serviceRegistry.clear();
        instance = null;
        getLogger().info("SaryuLibCore disabled.");
    }

    private void registerLibCommands() {
        CommandFramework.Root root = commandFramework.root(
                "saryulib", "saryulibcore.command.saryulib", "SaryuLibCore admin command");

        root.register(new CommandFramework.SubCommand() {
            @Override public String name() { return "info"; }
            @Override public String permission() { return "saryulibcore.command.saryulib"; }
            @Override public String description() { return "Show library status"; }
            @Override public String usage() { return "/saryulib info"; }
            @Override public boolean playerOnly() { return false; }
            @Override public int cooldownSeconds() { return 0; }

            @Override
            public void execute(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd,
                                String label, String[] args) {
                sender.sendMessage(messageModule.parse("&b=== SaryuLibCore v"
                        + getDescription().getVersion() + " ==="));
                sender.sendMessage(messageModule.parse("&7Database: &f" + databaseModule.getType()
                        + " (available=" + databaseModule.isAvailable() + ")"));
                sender.sendMessage(messageModule.parse("&7Packet layer: &f" + packetLayer.getBackendName()));
                sender.sendMessage(messageModule.parse("&7LuckPerms: &f" + permissionModule.hasLuckPerms()));
                sender.sendMessage(messageModule.parse("&7Language: &f" + languageManager.getActiveCode()));
            }
        });

        root.register(new CommandFramework.SubCommand() {
            @Override public String name() { return "reload"; }
            @Override public String permission() { return "saryulibcore.command.saryulib"; }
            @Override public String description() { return "Reload library config and languages"; }
            @Override public String usage() { return "/saryulib reload"; }
            @Override public boolean playerOnly() { return false; }
            @Override public int cooldownSeconds() { return 0; }

            @Override
            public void execute(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd,
                                String label, String[] args) {
                configModule.reload();
                languageManager.reload();
                punishmentService.reloadProvider();
                sender.sendMessage(messageModule.parse("&aSaryuLibCore reloaded."));
            }
        });

        root.bind(this);
    }

    public static @NotNull SaryuLib getInstance() {
        if (instance == null) {
            throw new IllegalStateException("SaryuLibCore is not enabled yet.");
        }
        return instance;
    }

    public static @Nullable SaryuLib getInstanceSafe() {
        return instance;
    }

    /**
     * Look up a shared service by type. Consuming plugins call this instead of
     * casting the plugin instance.
     */
    public <T> @Nullable T getService(@NotNull Class<T> type) {
        return serviceRegistry.get(type);
    }

    public void registerService(@NotNull Class<?> type, @NotNull Object service) {
        serviceRegistry.register(type, service);
    }

    public void unregisterService(@NotNull Class<?> type) {
        serviceRegistry.unregister(type);
    }

    public @NotNull ConfigModule getConfigModule() {
        return configModule;
    }

    public @NotNull LanguageManager getLanguageManager() {
        return languageManager;
    }

    public @NotNull MessageModule getMessageModule() {
        return messageModule;
    }

    public @NotNull DatabaseModule getDatabaseModule() {
        return databaseModule;
    }

    public @NotNull SchedulerModule getSchedulerModule() {
        return schedulerModule;
    }

    public @NotNull PacketLayer getPacketLayer() {
        return packetLayer;
    }

    public @NotNull PermissionModule getPermissionModule() {
        return permissionModule;
    }

    public @NotNull PunishmentService getPunishmentService() {
        return punishmentService;
    }

    public @NotNull IntegrationModule getIntegrationModule() {
        return integrationModule;
    }

    public @NotNull CommandFramework getCommandFramework() {
        return commandFramework;
    }

    /**
     * Convenience: short plugin prefix for logs / debug.
     */
    public @NotNull String getPluginPrefix() {
        return getDescription().getName();
    }
}
