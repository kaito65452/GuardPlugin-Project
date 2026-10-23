package id.saryu.libcore.language;

import id.saryu.libcore.SaryuLib;
import id.saryu.libcore.config.ConfigModule;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Language / i18n manager. Loads {@code languages/<code>.yml}, falls back to
 * {@code eng_US} when keys are missing, supports per-player locale, and hot reload.
 *
 * <p>Like {@link ConfigModule}, this can be scoped to a consuming plugin so each
 * plugin gets its own {@code plugins/<PluginName>/languages/} folder.</p>
 */
public class LanguageManager {

    public static final String DEFAULT_CODE = "eng_US";
    private static final String[] BUNDLED = {"eng_US", "id_ID"};

    private final JavaPlugin owner;
    private final ConfigModule config;
    private final Map<String, YamlConfiguration> messages = new LinkedHashMap<>();
    private String activeCode = DEFAULT_CODE;
    private boolean perPlayer = false;

    /** Language manager for SaryuLibCore itself. */
    public LanguageManager(@NotNull SaryuLib plugin) {
        this(plugin, plugin.getConfigModule());
    }

    /** Language manager scoped to a consuming plugin. */
    public LanguageManager(@NotNull JavaPlugin owner, @NotNull ConfigModule config) {
        this.owner = owner;
        this.config = config;
        ensureBundledFiles();
        loadLanguage(DEFAULT_CODE);
        loadLanguage("id_ID");
        reloadActive();
    }

    private void ensureBundledFiles() {
        File dir = new File(config.getDataFolder(), "languages");
        if (!dir.exists() && !dir.mkdirs()) {
            owner.getLogger().warning("Could not create languages directory " + dir.getAbsolutePath());
        }
        for (String code : BUNDLED) {
            File out = new File(dir, code + ".yml");
            if (out.exists()) continue;
            try (InputStream in = owner.getResource("languages/" + code + ".yml")) {
                if (in == null) continue;
                Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                owner.getLogger().info("Created languages/" + code + ".yml");
            } catch (IOException ex) {
                owner.getLogger().log(Level.WARNING, "Could not write languages/" + code + ".yml", ex);
            }
        }
    }

    public void reload() {
        ensureBundledFiles();
        messages.clear();
        loadLanguage(DEFAULT_CODE);
        loadLanguage("id_ID");
        // Also pick up any extra language files an admin dropped in.
        File dir = new File(config.getDataFolder(), "languages");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                String code = f.getName().substring(0, f.getName().length() - 4);
                if (!messages.containsKey(code)) loadLanguage(code);
            }
        }
        reloadActive();
        owner.getLogger().info("Language files reloaded (active=" + activeCode + ").");
    }

    private void loadLanguage(@NotNull String code) {
        File file = new File(config.getDataFolder(), "languages/" + code + ".yml");
        YamlConfiguration cfg;
        if (file.exists()) {
            cfg = YamlConfiguration.loadConfiguration(file);
            mergeBundledDefaults(code, cfg);
        } else {
            // Fall back to bundled resource.
            try (InputStream in = owner.getResource("languages/" + code + ".yml")) {
                if (in == null) {
                    owner.getLogger().warning("No language resource for " + code);
                    return;
                }
                cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (Exception ex) {
                owner.getLogger().log(Level.WARNING, "Failed to load language " + code, ex);
                return;
            }
        }
        messages.put(code, cfg);
    }

    /**
     * Add keys introduced by a plugin update to an existing on-disk language file.
     * The merge is in-memory so user edits (and comments) on disk are untouched;
     * delete the file to regenerate it from the bundled resource.
     */
    private void mergeBundledDefaults(@NotNull String code, @NotNull YamlConfiguration target) {
        try (InputStream in = owner.getResource("languages/" + code + ".yml")) {
            if (in == null) return;
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : bundled.getKeys(true)) {
                if (bundled.isConfigurationSection(key) || target.contains(key)) continue;
                target.set(key, bundled.get(key));
                changed = true;
            }
            if (changed) {
                owner.getLogger().info("Merged new language keys for " + code
                        + " in memory (delete languages/" + code + ".yml to regenerate it).");
            }
        } catch (Exception ex) {
            owner.getLogger().log(Level.FINE, "Could not merge bundled language defaults for " + code, ex);
        }
    }

    private void reloadActive() {
        activeCode = config.getString("language", DEFAULT_CODE);
        if (!messages.containsKey(activeCode)) {
            activeCode = DEFAULT_CODE;
        }
        perPlayer = config.getBoolean("language-per-player", false);
    }

    public @NotNull String getActiveCode() {
        return activeCode;
    }

    public boolean isPerPlayer() {
        return perPlayer;
    }

    public @NotNull Map<String, YamlConfiguration> getLoaded() {
        return Collections.unmodifiableMap(messages);
    }

    /**
     * Read a raw configuration section (not under {@code messages.}) from the
     * active language, falling back to English. Used for alert templates.
     */
    public @Nullable org.bukkit.configuration.ConfigurationSection section(@NotNull String path) {
        YamlConfiguration active = messages.get(activeCode);
        org.bukkit.configuration.ConfigurationSection found =
                active != null ? active.getConfigurationSection(path) : null;
        if (found == null) {
            YamlConfiguration def = messages.get(DEFAULT_CODE);
            found = def != null ? def.getConfigurationSection(path) : null;
        }
        return found;
    }

    public boolean hasCode(@NotNull String code) {
        return messages.containsKey(code);
    }

    public void setActiveCode(@NotNull String code) {
        String normalized = normalizeCode(code);
        if (messages.containsKey(normalized)) {
            activeCode = normalized;
            config.get().set("language", normalized);
            config.save();
        }
    }

    /**
     * Normalize {@code en_us}, {@code EN_US}, {@code eng_US} ... into a loaded code.
     */
    public @NotNull String normalizeCode(@NotNull String code) {
        String input = code.trim();
        if (messages.containsKey(input)) return input;
        String upper = input.toUpperCase(Locale.ROOT);
        if (messages.containsKey(upper)) return upper;
        for (String key : messages.keySet()) {
            if (key.equalsIgnoreCase(input)) return key;
        }
        if (upper.equals("EN") || upper.equals("EN_US")) return DEFAULT_CODE;
        if (upper.equals("ID") || upper.equals("ID_ID")) return "id_ID";
        return input;
    }

    /**
     * Raw message string for a key, with fallback to active language then English,
     * then the key itself.
     */
    public @NotNull String get(@NotNull String key) {
        YamlConfiguration active = messages.get(activeCode);
        String val = active != null ? active.getString(key) : null;
        if (val == null || val.isEmpty()) {
            YamlConfiguration def = messages.get(DEFAULT_CODE);
            val = def != null ? def.getString(key) : null;
        }
        return val != null ? val : key;
    }

    public @NotNull String get(@NotNull String key, @NotNull String def) {
        String val = get(key);
        return val.equals(key) ? def : val;
    }

    /**
     * Message lookup that checks {@code messages.<key>} first (the layout used by
     * the bundled language files), then the plain key.
     */
    public @NotNull String message(@NotNull String key, @NotNull String def) {
        String nested = get("messages." + key);
        if (!nested.equals("messages." + key)) return nested;
        return get(key, def);
    }

    public @NotNull String getPlayerCode(@NotNull org.bukkit.entity.Player player) {
        if (!perPlayer) {
            return activeCode;
        }
        String locale = player.getLocale().replace('-', '_').toUpperCase(Locale.ROOT);
        String normalized = normalizeCode(locale);
        if (messages.containsKey(normalized)) {
            return normalized;
        }
        String base = locale.substring(0, Math.min(2, locale.length()));
        normalized = normalizeCode(base);
        if (messages.containsKey(normalized)) {
            return normalized;
        }
        return activeCode;
    }

    public @NotNull String getForPlayer(@NotNull org.bukkit.entity.Player player, @NotNull String key) {
        String code = getPlayerCode(player);
        YamlConfiguration cfg = messages.get(code);
        String val = cfg != null ? cfg.getString(key) : null;
        if (val == null || val.isEmpty()) {
            val = get(key);
        }
        return val != null ? val : key;
    }
}
