package id.saryu.saryuguard.locale;

import id.saryu.saryuguard.SaryuGuardPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Level;

/**
 * Localization manager for SaryuGuard supporting Adventure MiniMessage,
 * bilingual templates (en / id), and variable replacements.
 */
public class LocaleManager {

    private final SaryuGuardPlugin plugin;
    private final Map<String, YamlConfiguration> locales = new HashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private String currentLocale = "id";

    public LocaleManager(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void loadLocales() {
        locales.clear();
        File localeFolder = new File(plugin.getDataFolder(), "locales");
        if (!localeFolder.exists()) {
            localeFolder.mkdirs();
        }

        // Copy default locale files from resources if not present
        String[] defaultFiles = {"id.yml", "en.yml"};
        for (String fileName : defaultFiles) {
            File target = new File(localeFolder, fileName);
            if (!target.exists()) {
                try (InputStream in = plugin.getResource("locales/" + fileName)) {
                    if (in != null) {
                        Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Gagal menyalin resource default locales/" + fileName + ": " + e.getMessage());
                }
            }
        }

        // Load all .yml in locales folder
        File[] files = localeFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
                    YamlConfiguration cfg = YamlConfiguration.loadConfiguration(reader);
                    String baseName = file.getName().substring(0, file.getName().lastIndexOf('.'));
                    locales.put(baseName.toLowerCase(Locale.ROOT), cfg);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Gagal memuat file bahasa " + file.getName(), e);
                }
            }
        }

        // Determine current locale from plugin config
        String cfgLocale = plugin.getConfigManager().getConfig().getString("locale", "id").toLowerCase(Locale.ROOT);
        if (cfgLocale.startsWith("en")) {
            this.currentLocale = "en";
        } else if (cfgLocale.startsWith("id")) {
            this.currentLocale = "id";
        } else {
            this.currentLocale = cfgLocale;
        }

        if (!locales.containsKey(currentLocale) && !locales.containsKey("en") && !locales.containsKey("id")) {
            plugin.getLogger().warning("File bahasa untuk '" + currentLocale + "' tidak ditemukan. Fallback ke default.");
        }
    }

    public @NotNull String getCurrentLocale() {
        return currentLocale;
    }

    public void setDefaultLocale(@NotNull String locale) {
        this.currentLocale = locale.toLowerCase(Locale.ROOT);
        plugin.getConfigManager().getConfig().set("locale", currentLocale);
        plugin.getConfigManager().saveConfig();
    }

    public @NotNull String getRaw(@NotNull String key, @NotNull String fallback) {
        YamlConfiguration active = locales.get(currentLocale);
        if (active != null && active.contains(key)) {
            return active.getString(key, fallback);
        }
        // Fallback to English, then Indonesian
        YamlConfiguration en = locales.get("en");
        if (en != null && en.contains(key)) {
            return en.getString(key, fallback);
        }
        YamlConfiguration id = locales.get("id");
        if (id != null && id.contains(key)) {
            return id.getString(key, fallback);
        }
        return fallback;
    }

    public @NotNull List<String> getRawList(@NotNull String key) {
        YamlConfiguration active = locales.get(currentLocale);
        if (active != null && active.contains(key)) {
            return active.getStringList(key);
        }
        YamlConfiguration en = locales.get("en");
        if (en != null && en.contains(key)) {
            return en.getStringList(key);
        }
        YamlConfiguration id = locales.get("id");
        if (id != null && id.contains(key)) {
            return id.getStringList(key);
        }
        return Collections.emptyList();
    }

    public @NotNull String getMessage(@NotNull String key, String... replacements) {
        String template = getRaw(key, "<missing: " + key + ">");
        return replaceVariables(template, (Object[]) replacements);
    }

    public @NotNull Component message(@NotNull String key, @NotNull String fallback, Object... replacements) {
        String template = getRaw(key, fallback);
        String formatted = replaceVariables(template, replacements);
        return parseComponent(formatted);
    }

    public @NotNull Component message(@NotNull String key, Object... replacements) {
        return message(key, "<missing: " + key + ">", replacements);
    }

    public void send(@NotNull CommandSender sender, @NotNull String key, @NotNull String fallback, Object... replacements) {
        String template = getRaw(key, fallback);
        String prefix = getRaw("prefix", "<dark_gray>[<gold>Saryu<red>Guard<dark_gray>]<reset> ");
        String formatted = replaceVariables(prefix + template, replacements);
        sender.sendMessage(parseComponent(formatted));
    }

    public void send(@NotNull CommandSender sender, @NotNull String key, Object... replacements) {
        send(sender, key, "<missing: " + key + ">", replacements);
    }

    public @NotNull Component parseComponent(@NotNull String text) {
        try {
            return miniMessage.deserialize(text);
        } catch (Exception e) {
            return Component.text(text);
        }
    }

    private String replaceVariables(String template, Object... replacements) {
        if (template == null) return "";
        if (replacements == null || replacements.length == 0) return template;

        String result = template;
        for (int i = 0; i < replacements.length; i += 2) {
            String k = String.valueOf(replacements[i]);
            String v = (i + 1 < replacements.length && replacements[i + 1] != null)
                    ? String.valueOf(replacements[i + 1])
                    : "";
            result = result.replace("{" + k + "}", v);
        }
        return result;
    }
}
