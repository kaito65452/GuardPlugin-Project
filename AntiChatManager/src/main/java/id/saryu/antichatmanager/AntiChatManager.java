package id.saryu.antichatmanager;

import id.saryu.antichatmanager.blacklist.LiteBlacklistManager;
import id.saryu.antichatmanager.command.ACMCommandLite;
import id.saryu.antichatmanager.listener.LiteCommandListener;
import id.saryu.antichatmanager.migration.MigrationManager;
import id.saryu.antichatmanager.policy.PolicyStatus;
import id.saryu.antichatmanager.storage.LiteStorage;
import id.saryu.libcore.BuildInfo;
import id.saryu.libcore.SaryuLib;
import id.saryu.libcore.config.ConfigModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * AntiChatManager Lite 4.0.0.
 *
 * <p>Focused, high-performance command blacklist for Paper servers.
 * Features:
 * - Immutable AtomicReference blacklist snapshots (zero DB queries on command execution).
 * - Multi-tiered policy status (READY, DEGRADED, UNAVAILABLE).
 * - Automatic 3.x idempotent migration with emergency blacklist fail-safe.
 * - Literal command matching with namespace and CommandMap alias resolution.</p>
 */
public class AntiChatManager extends JavaPlugin {

    public static final String PERM_ADMIN = "antichatmanager.admin";
    public static final String PERM_BYPASS = "antichatmanager.bypass";

    private static AntiChatManager instance;

    private SaryuLib lib;
    private BuildInfo buildInfo;
    private FileConfiguration config;
    private FileConfiguration databaseConfig;
    private FileConfiguration messagesConfig;

    private LiteStorage storage;
    private LiteBlacklistManager blacklistManager;
    private LiteCommandListener commandListener;
    private ACMCommandLite commandHandler;
    private MigrationManager migrationManager;

    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        instance = this;
        buildInfo = BuildInfo.load(AntiChatManager.class, "4.0.0");

        // 1. Check SaryuLibCore
        lib = SaryuLib.getInstanceSafe();
        if (lib == null) {
            getLogger().severe("SaryuLibCore tidak ditemukan! AntiChatManager Lite membutuhkan SaryuLibCore untuk berjalan.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Load configurations: config.yml, database.yml, messages.yml
        loadConfigFiles();

        // 3. Reconfigure shared DB pool if database.yml specifies settings
        try {
            File dbConfigFile = new File(getDataFolder(), "database.yml");
            if (dbConfigFile.exists()) {
                ConfigModule dbCfgModule = new ConfigModule(this, "database.yml");
                lib.getDatabaseModule().reconfigure(dbCfgModule);
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Gagal mengonfigurasi pool database dari database.yml: " + t.getMessage(), t);
        }

        // 4. Initialize storage & Blacklist Manager
        storage = new LiteStorage(this, lib.getDatabaseModule());
        storage.init();

        blacklistManager = new LiteBlacklistManager(this, storage);
        if (config != null) {
            List<String> emergencyList = config.getStringList("emergency-blacklist");
            blacklistManager.setEmergencyList(emergencyList);
        }

        // 5. Run 3.x Migration (idempotent, never deletes)
        migrationManager = new MigrationManager(this, storage, lib.getDatabaseModule());
        migrationManager.runMigration();

        // 6. Load initial blacklist policy from database
        blacklistManager.loadPolicy().join();

        // 7. Register command listener
        commandListener = new LiteCommandListener(this, blacklistManager);
        if (config != null && messagesConfig != null) {
            commandListener.reloadConfig(config, messagesConfig);
        }
        Bukkit.getPluginManager().registerEvents(commandListener, this);

        // 8. Register /acm commands
        commandHandler = new ACMCommandLite(this, blacklistManager);
        commandHandler.register(lib.getCommandFramework());

        getLogger().info("AntiChatManager Lite " + buildInfo.display() + " aktif. "
                + "Policy Status: " + blacklistManager.getStatus()
                + " (" + blacklistManager.getSnapshot().size() + " commands blacklisted).");
    }

    @Override
    public void onDisable() {
        instance = null;
        getLogger().info("AntiChatManager Lite dinonaktifkan.");
    }

    private void loadConfigFiles() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // config.yml
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResourceIfNotExists("config.yml", configFile);
        }
        this.config = YamlConfiguration.loadConfiguration(configFile);

        // database.yml
        File dbFile = new File(getDataFolder(), "database.yml");
        if (!dbFile.exists()) {
            saveResourceIfNotExists("database.yml", dbFile);
        }
        this.databaseConfig = YamlConfiguration.loadConfiguration(dbFile);

        // messages.yml (never overwritten)
        File msgFile = new File(getDataFolder(), "messages.yml");
        if (!msgFile.exists()) {
            saveResourceIfNotExists("messages.yml", msgFile);
        }
        this.messagesConfig = YamlConfiguration.loadConfiguration(msgFile);
    }

    private void saveResourceIfNotExists(String resourceName, File destination) {
        if (!destination.exists()) {
            try (InputStream in = getResource(resourceName)) {
                if (in != null) {
                    Files.copy(in, destination.toPath());
                } else {
                    destination.createNewFile();
                }
            } catch (Exception e) {
                getLogger().warning("Could not save resource " + resourceName + ": " + e.getMessage());
            }
        }
    }

    public synchronized CompletableFuture<Boolean> reloadLite() {
        loadConfigFiles();
        if (commandListener != null && config != null && messagesConfig != null) {
            commandListener.reloadConfig(config, messagesConfig);
        }
        if (blacklistManager != null) {
            if (config != null) {
                blacklistManager.setEmergencyList(config.getStringList("emergency-blacklist"));
            }
            return blacklistManager.loadPolicy();
        }
        return CompletableFuture.completedFuture(false);
    }

    public @NotNull Component formatMessage(@NotNull String key, @NotNull String defaultTemplate, Object... replacements) {
        String template = messagesConfig != null ? messagesConfig.getString(key, defaultTemplate) : defaultTemplate;
        String prefix = messagesConfig != null ? messagesConfig.getString("prefix", "<dark_gray>[<aqua>ACM<dark_gray>]<reset> ") : "";

        String msg = prefix + template;
        if (replacements != null && replacements.length > 0) {
            for (int i = 0; i < replacements.length; i += 2) {
                String k = String.valueOf(replacements[i]);
                String v = (i + 1 < replacements.length) ? String.valueOf(replacements[i + 1]) : "";
                msg = msg.replace("{" + k + "}", v);
            }
        }

        try {
            return miniMessage.deserialize(msg);
        } catch (Exception e) {
            return Component.text(msg);
        }
    }

    public static AntiChatManager getInstance() {
        return instance;
    }

    public SaryuLib getLib() { return lib; }
    public BuildInfo getBuildInfo() { return buildInfo; }
    public LiteBlacklistManager getBlacklistManager() { return blacklistManager; }
    public LiteStorage getStorage() { return storage; }
}
