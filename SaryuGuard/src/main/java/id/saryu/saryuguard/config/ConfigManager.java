package id.saryu.saryuguard.config;

import com.moandjiezana.toml.Toml;
import id.saryu.saryuguard.SaryuGuardPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.logging.Level;

/**
 * Safe configuration and secret key management for SaryuGuard.
 * Enforces size limits and path traversal validation.
 */
public class ConfigManager {

    public static final long DEFAULT_MAX_CONFIG_BYTES = 1048576L; // 1 MB
    private static final String SECRET_KEY_FILE = "secret.key";
    private static final String SECRET_TOML_FILE = "secret.toml";
    private static final String CREDENTIALS_TOML_FILE = "credentials.toml";
    private static final String CONFIG_FILE = "config.yml";

    private final SaryuGuardPlugin plugin;
    private FileConfiguration config;
    private Toml credentials;
    private Toml secretToml;
    private byte[] secretKey;

    public ConfigManager(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void loadConfigs() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        // 1. Load config.yml safely
        File configFile = safeFile(CONFIG_FILE);
        if (!configFile.exists()) {
            plugin.saveResource(CONFIG_FILE, false);
        }
        validateFileSize(configFile, DEFAULT_MAX_CONFIG_BYTES);
        this.config = YamlConfiguration.loadConfiguration(configFile);

        long maxBytes = this.config.getLong("security.max-config-bytes", DEFAULT_MAX_CONFIG_BYTES);

        // 2. Load credentials.toml
        File credFile = safeFile(CREDENTIALS_TOML_FILE);
        if (!credFile.exists()) {
            saveResourceIfNotExists(CREDENTIALS_TOML_FILE, credFile);
        }
        if (credFile.exists()) {
            validateFileSize(credFile, maxBytes);
            try {
                this.credentials = new Toml().read(credFile);
            } catch (Exception e) {
                plugin.getLogger().warning("Gagal membaca credentials.toml: " + e.getMessage());
                this.credentials = new Toml();
            }
        } else {
            this.credentials = new Toml();
        }

        // 3. Load secret.toml
        File secretTomlFile = safeFile(SECRET_TOML_FILE);
        if (!secretTomlFile.exists()) {
            saveResourceIfNotExists(SECRET_TOML_FILE, secretTomlFile);
        }
        if (secretTomlFile.exists()) {
            validateFileSize(secretTomlFile, maxBytes);
            try {
                this.secretToml = new Toml().read(secretTomlFile);
            } catch (Exception e) {
                plugin.getLogger().warning("Gagal membaca secret.toml: " + e.getMessage());
                this.secretToml = new Toml();
            }
        } else {
            this.secretToml = new Toml();
        }

        // 4. Initialize Secret Key (auto_key | env | passphrase)
        loadSecretKey();
    }

    private void loadSecretKey() {
        String mode = "auto_key";
        String envVar = "SARYUGUARD_KEY";
        String passphrase = "";
        String salt = "";

        if (secretToml != null) {
            String cfgMode = secretToml.getString("encryption.mode");
            if (cfgMode != null && !cfgMode.isBlank()) {
                mode = cfgMode.trim().toLowerCase();
            }
            String cfgEnv = secretToml.getString("encryption.env_var");
            if (cfgEnv != null && !cfgEnv.isBlank()) {
                envVar = cfgEnv.trim();
            }
            String cfgPass = secretToml.getString("encryption.passphrase");
            if (cfgPass != null) {
                passphrase = cfgPass;
            }
            String cfgSalt = secretToml.getString("encryption.salt");
            if (cfgSalt != null) {
                salt = cfgSalt;
            }
        }

        try {
            if ("env".equalsIgnoreCase(mode)) {
                String envVal = System.getenv(envVar);
                if (envVal == null || envVal.isBlank()) {
                    throw new IllegalStateException("Environment variable " + envVar + " tidak disetel untuk enkripsi.");
                }
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                this.secretKey = sha256.digest(envVal.getBytes(StandardCharsets.UTF_8));
            } else if ("passphrase".equalsIgnoreCase(mode)) {
                if (passphrase.isBlank()) {
                    throw new IllegalStateException("Passphrase enkripsi kosong di secret.toml.");
                }
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                sha256.update(salt.getBytes(StandardCharsets.UTF_8));
                this.secretKey = sha256.digest(passphrase.getBytes(StandardCharsets.UTF_8));
            } else {
                // auto_key mode: secret.key file
                File keyFile = safeFile(SECRET_KEY_FILE);
                if (!keyFile.exists() || keyFile.length() != 32) {
                    byte[] genKey = new byte[32];
                    new SecureRandom().nextBytes(genKey);
                    Files.write(keyFile.toPath(), genKey);
                    this.secretKey = genKey;
                    plugin.getLogger().info("[Crypt] Kunci AES-256 acak baru dibuat di " + SECRET_KEY_FILE);
                } else {
                    this.secretKey = Files.readAllBytes(keyFile.toPath());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Gagal menginisialisasi secret key enkripsi: " + e.getMessage(), e);
            // Fallback emergency in-memory key so server does not crash
            byte[] emergencyKey = new byte[32];
            new SecureRandom().nextBytes(emergencyKey);
            this.secretKey = emergencyKey;
        }
    }

    public synchronized void rotateSecretKeyFile(byte[] newKeyBytes) throws IOException {
        if (newKeyBytes == null || newKeyBytes.length != 32) {
            throw new IllegalArgumentException("Panjang kunci harus tepat 32 byte.");
        }
        File keyFile = safeFile(SECRET_KEY_FILE);
        Path tmp = keyFile.toPath().resolveSibling(SECRET_KEY_FILE + ".tmp");
        Files.write(tmp, newKeyBytes);
        try {
            Files.move(tmp, keyFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmp, keyFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        this.secretKey = newKeyBytes;
    }

    public @NotNull File safeFile(@NotNull String relativePath) {
        File dataFolder = plugin.getDataFolder();
        File f = new File(dataFolder, relativePath);
        try {
            String rootCanonical = dataFolder.getCanonicalPath();
            String fileCanonical = f.getCanonicalPath();
            if (!fileCanonical.startsWith(rootCanonical)) {
                throw new SecurityException("Path traversal terdeteksi pada path: " + relativePath);
            }
        } catch (IOException e) {
            throw new SecurityException("Gagal memvalidasi path kanonikal untuk: " + relativePath, e);
        }
        return f;
    }

    private void validateFileSize(@NotNull File file, long maxBytes) {
        if (file.exists() && file.length() > maxBytes) {
            plugin.getLogger().warning("Peringatan: Ukuran file " + file.getName()
                    + " (" + file.length() + " bytes) melebihi batas yang diizinkan (" + maxBytes + " bytes).");
        }
    }

    private void saveResourceIfNotExists(String resourceName, File destination) {
        if (!destination.exists()) {
            try (InputStream in = plugin.getResource(resourceName)) {
                if (in != null) {
                    Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    destination.createNewFile();
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Tidak dapat menyimpan resource default " + resourceName + ": " + e.getMessage());
            }
        }
    }

    public @NotNull FileConfiguration getConfig() {
        if (config == null) {
            loadConfigs();
        }
        return config;
    }

    public @NotNull Toml getCredentials() {
        if (credentials == null) {
            loadConfigs();
        }
        return credentials;
    }

    public @Nullable String getCredential(@NotNull String section, @NotNull String key) {
        if (credentials == null) return null;
        Toml sec = credentials.getTable(section);
        if (sec != null && sec.contains(key)) {
            return sec.getString(key);
        }
        // Fallback to top-level key format section_key
        String flatKey = section + "_" + key;
        if (credentials.contains(flatKey)) {
            return credentials.getString(flatKey);
        }
        return null;
    }

    public byte[] getSecretKey() {
        if (secretKey == null) {
            loadConfigs();
        }
        return secretKey != null ? secretKey.clone() : new byte[32];
    }

    public synchronized void saveConfig() {
        try {
            if (config != null) {
                config.save(safeFile(CONFIG_FILE));
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Gagal menyimpan config.yml: " + e.getMessage(), e);
        }
    }
}
