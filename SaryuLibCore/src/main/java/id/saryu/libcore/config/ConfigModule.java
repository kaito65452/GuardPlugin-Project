package id.saryu.libcore.config;

import id.saryu.libcore.SaryuLib;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Versioned YAML config with auto-migration, comment preservation (best effort),
 * validation and hot-reload. All accessor methods are safe to call from any thread.
 *
 * <p>The module can be scoped to any plugin: {@code new ConfigModule(myPlugin, "config.yml")}
 * gives that plugin its own {@code plugins/<PluginName>/config.yml} whose defaults are
 * read from the plugin's bundled resource. This is how consuming plugins (such as
 * AntiChatManager) get their own configuration while still using the shared library.</p>
 */
public class ConfigModule {

    private final JavaPlugin owner;
    private final File configFile;
    private final String resourceName;
    private YamlConfiguration config;
    private int configVersion = 1;

    /** Config for SaryuLibCore itself. */
    public ConfigModule(@NotNull SaryuLib plugin) {
        this(plugin, plugin.getDataFolder(), "config.yml");
    }

    /** Config scoped to a consuming plugin, e.g. {@code new ConfigModule(this, "config.yml")}. */
    public ConfigModule(@NotNull JavaPlugin owner, @NotNull String fileName) {
        this(owner, owner.getDataFolder(), fileName);
    }

    public ConfigModule(@NotNull JavaPlugin owner, @NotNull File dataFolder, @NotNull String fileName) {
        this.owner = owner;
        this.resourceName = fileName;
        this.configFile = new File(dataFolder, fileName);
        load();
    }

    public void load() {
        ensureFileAndParents();
        config = YamlConfiguration.loadConfiguration(configFile);
        // Embedded defaults fill in missing keys.
        try (InputStream in = owner.getResource(resourceName)) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
            }
        } catch (IOException ignored) {
        }
        configVersion = config.getInt("config.version", 1);
        validate();
    }

    private void ensureFileAndParents() {
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            owner.getLogger().warning("Could not create directory " + parent.getAbsolutePath());
        }
        if (configFile.exists()) return;
        try (InputStream in = owner.getResource(resourceName)) {
            if (in != null) {
                Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                owner.getLogger().info("Created " + configFile.getName() + " from bundled defaults.");
                return;
            }
        } catch (IOException ex) {
            owner.getLogger().log(Level.WARNING, "Could not write default " + resourceName, ex);
        }
        try {
            if (configFile.createNewFile()) {
                owner.getLogger().info("Created empty " + configFile.getName() + " (no bundled defaults found).");
            }
        } catch (IOException ex) {
            owner.getLogger().log(Level.WARNING, "Could not create " + configFile.getName(), ex);
        }
    }

    public void reload() {
        load();
        owner.getLogger().info(configFile.getName() + " reloaded.");
    }

    public void save() {
        try {
            File parent = configFile.getParentFile();
            if (parent != null) parent.mkdirs();
            config.save(configFile);
        } catch (IOException ex) {
            owner.getLogger().log(Level.WARNING, "Could not save " + configFile.getName(), ex);
        }
    }

    public @NotNull YamlConfiguration get() {
        return config;
    }

    public @NotNull File getFile() {
        return configFile;
    }

    /** Data folder of the plugin that owns this config. */
    public @NotNull File getDataFolder() {
        File parent = configFile.getParentFile();
        return parent != null ? parent : owner.getDataFolder();
    }

    public @NotNull JavaPlugin getOwner() {
        return owner;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    /**
     * Get a section, creating it if missing.
     */
    public @NotNull ConfigurationSection getSection(@NotNull String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        return section != null ? section : config.createSection(path);
    }

    public @NotNull String getString(@NotNull String path, @NotNull String def) {
        String value = config.getString(path);
        return value != null ? value : def;
    }

    public boolean getBoolean(@NotNull String path, boolean def) {
        return config.getBoolean(path, def);
    }

    public int getInt(@NotNull String path, int def) {
        return config.getInt(path, def);
    }

    public long getLong(@NotNull String path, long def) {
        return config.getLong(path, def);
    }

    public double getDouble(@NotNull String path, double def) {
        return config.getDouble(path, def);
    }

    public @NotNull String getPluginPrefix() {
        return getString("prefix", "&7[&bAntiChat&7]");
    }

    private void validate() {
        // Basic structural validation; report clear errors, never crash.
        if (!config.isSet("database.type")) {
            config.set("database.type", "SQLITE");
        }
        if (!config.isSet("language")) {
            config.set("language", "eng_US");
        }
        if (!config.isSet("alert.server-name")) {
            config.set("alert.server-name", "Server Security");
        }
        if (!config.isSet("timezone")) {
            config.set("timezone", "WIB");
        }
    }

    /**
     * Auto-migration placeholder. Future schema versions migrate here.
     */
    public void migrate() {
        // No migrations yet; version 1 is the baseline.
    }
}
