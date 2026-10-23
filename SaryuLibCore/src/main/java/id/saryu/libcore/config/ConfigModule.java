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

    private static final int CURRENT_CONFIG_VERSION = 4;
    private static final java.util.regex.Pattern ENV_PATTERN =
            java.util.regex.Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");

    private final JavaPlugin owner;
    private final File configFile;
    private final String resourceName;
    private YamlConfiguration config;
    private YamlConfiguration bundledDefaults;
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
        bundledDefaults = new YamlConfiguration();
        try (InputStream in = owner.getResource(resourceName)) {
            if (in != null) {
                bundledDefaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                config.setDefaults(bundledDefaults);
            }
        } catch (IOException ignored) {
        }
        configVersion = config.getInt("config.version", 1);
        validate();
        migrate();
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
        return value != null ? resolveSecrets(value) : def;
    }

    public @NotNull java.util.List<String> getStringList(@NotNull String path) {
        java.util.List<String> values = config.getStringList(path);
        if (values.isEmpty()) return values;
        java.util.List<String> resolved = new java.util.ArrayList<>(values.size());
        for (String value : values) resolved.add(resolveSecrets(value));
        return resolved;
    }

    /**
     * Supports {@code ${env:NAME}} so database passwords and webhook tokens do
     * not have to be stored as plain text in config.yml. Missing variables are
     * replaced with an empty string (fail closed) instead of crashing.
     */
    public @NotNull String resolveSecrets(@NotNull String value) {
        if (value == null || !value.contains("${env:")) return value == null ? "" : value;
        java.util.regex.Matcher matcher = ENV_PATTERN.matcher(value);
        StringBuffer out = new StringBuffer(value.length());
        while (matcher.find()) {
            String env = System.getenv(matcher.group(1));
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(env == null ? "" : env));
        }
        matcher.appendTail(out);
        return out.toString();
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
        // Basic structural validation; report clear errors, never crash, and
        // fall back to safe defaults when a user edited an invalid value.
        if (!config.isSet("database.type")) {
            config.set("database.type", "SQLITE");
        }
        String dbType = config.getString("database.type", "SQLITE");
        if (dbType == null || !dbType.matches("(?i)MYSQL|H2|SQLITE")) {
            owner.getLogger().warning("Invalid database.type '" + dbType + "'; using SQLITE.");
            config.set("database.type", "SQLITE");
        }
        if (!config.isSet("language")) {
            config.set("language", "eng_US");
        }
        if (!config.isSet("alert.server-name")) {
            config.set("alert.server-name", "Server Security");
        }
        String timezone = config.getString("timezone", "WIB");
        if (timezone == null || !timezone.matches("(?i)WIB|WITA|WIT|UTC")) {
            owner.getLogger().warning("Invalid timezone '" + timezone + "'; using WIB.");
            config.set("timezone", "WIB");
        }
        int pool = config.getInt("database.pool-size", 10);
        if (pool < 1 || pool > 64) {
            owner.getLogger().warning("database.pool-size must be 1-64; using 10.");
            config.set("database.pool-size", 10);
        }
        int maxLength = config.getInt("security.max-command-length", 256);
        if (maxLength < 32 || maxLength > 4096) {
            owner.getLogger().warning("security.max-command-length must be 32-4096; using 256.");
            config.set("security.max-command-length", 256);
        }
        validateDuration("punishment.count-window", "60m");
        validateDuration("conflict-detection.reminder-interval", "6h");
        validateDuration("audit.notify-interval", "6h");
    }

    private void validateDuration(@NotNull String path, @NotNull String def) {
        if (!config.isSet(path)) return;
        String value = config.getString(path);
        if (value != null && value.equals("0")) return;
        if (value == null || !value.matches("(?i)(\\d+\\s*(s|m|h|d|w|mo|y)\\s*)+|perm")) {
            owner.getLogger().warning("Invalid duration at " + path + " ('" + value + "'); using " + def + ".");
            config.set(path, def);
        }
    }

    /**
     * Add keys introduced by newer versions without overwriting any user value.
     * Version 1 is the original layout; versions 2-4 add v7
     * security/scan/audit/plugin-identity, command action and integration sections. Comments in the user's file are preserved because we only write
     * when new keys actually have to be added.
     */
    public void migrate() {
        if (configVersion >= CURRENT_CONFIG_VERSION) return;
        boolean changed = false;
        if (bundledDefaults != null && !bundledDefaults.getKeys(true).isEmpty()) {
            // config.contains() also consults the defaults, so use a raw copy of
            // the user's file to decide what is actually missing on disk.
            YamlConfiguration raw = YamlConfiguration.loadConfiguration(configFile);
            for (String key : bundledDefaults.getKeys(true)) {
                if (bundledDefaults.isConfigurationSection(key) || raw.contains(key)) continue;
                config.set(key, bundledDefaults.get(key));
                changed = true;
            }
        }
        config.set("config.version", CURRENT_CONFIG_VERSION);
        configVersion = CURRENT_CONFIG_VERSION;
        changed = true;
        if (changed) {
            save();
            owner.getLogger().info("Migrated " + configFile.getName()
                    + " to config version " + CURRENT_CONFIG_VERSION + " (new keys added).");
        }
    }
}
