package id.saryu.antichatmanager.security;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.libcore.permission.PermissionModule;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Read-only security audit. It never edits server configuration or permission
 * data; findings are warnings for staff to review.
 */
public class SecurityAudit {

    public static class Finding {
        public String level;
        public String message;
    }

    private final AntiChatManager plugin;
    private volatile String selfHash;

    public SecurityAudit(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
    }

    public void runStartupAudit() {
        List<Finding> findings = audit();
        for (Finding f : findings) {
            if ("CRITICAL".equals(f.level)) {
                plugin.getLogger().severe("[SECURITY CRITICAL] " + f.message);
            } else if ("WARNING".equals(f.level)) {
                plugin.getLogger().warning("[SECURITY WARNING] " + f.message);
            }
        }
    }

    public @NotNull List<Finding> audit() {
        List<Finding> findings = new ArrayList<>();
        String hash = selfHash();
        if (hash != null) {
            findings.add(finding("OK", "Plugin JAR SHA-256: " + hash));
        }
        if (!Bukkit.getOnlineMode()) {
            findings.add(finding("CRITICAL", "Server is in offline-mode. If a proxy is used, enable modern forwarding; otherwise identity spoofing is possible."));
        }
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            findings.add(finding("WARNING", "LuckPerms not found; conflict detection and permission audit are limited."));
        }
        if (Bukkit.getPluginManager().getPlugin("PacketEvents") == null) {
            findings.add(finding("WARNING", "PacketEvents not found; packet-level command filtering is unavailable (Bukkit events only)."));
        }
        int ops = 0;
        for (org.bukkit.OfflinePlayer op : Bukkit.getOperators()) if (op.isOp()) ops++;
        if (ops > 0) {
            findings.add(finding("WARNING", ops + " operator(s) exist. Ensure bypass/OP policy matches your configuration."));
        }
        auditServerProperties(findings);
        auditPluginJarBaseline(findings);
        auditLuckPermsHolders(findings);
        auditExecutableFiles(findings);
        if (findings.isEmpty()) findings.add(finding("OK", "Basic security checks completed."));
        return findings;
    }

    public @Nullable String selfHash() {
        String cached = selfHash;
        if (cached != null) return cached;
        cached = jarHash(selfJar());
        selfHash = cached;
        return cached;
    }

    private void auditServerProperties(@NotNull List<Finding> findings) {
        File root = plugin.getDataFolder().getParentFile() == null
                ? null : plugin.getDataFolder().getParentFile().getParentFile();
        if (root == null) return;
        File propertiesFile = new File(root, "server.properties");
        if (!propertiesFile.isFile()) return;
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(propertiesFile)) {
            props.load(in);
        } catch (IOException ex) {
            findings.add(finding("WARNING", "Could not read server.properties: " + ex.getMessage()));
            return;
        }
        boolean offline = !Boolean.parseBoolean(props.getProperty("online-mode", "true"));
        boolean velocity = readBoolean(new File(root, "config/paper-global.yml"),
                "proxies.velocity.enabled");
        boolean bungee = readBoolean(new File(root, "spigot.yml"),
                "settings.bungeecord");
        if (offline && !velocity && !bungee) {
            findings.add(finding("CRITICAL",
                    "online-mode=false without Velocity/BungeeCord forwarding; player names and UUIDs can be spoofed."));
        }
        if (Boolean.parseBoolean(props.getProperty("enable-rcon", "false"))) {
            String password = props.getProperty("rcon.password", "");
            if (password.isBlank()) {
                findings.add(finding("CRITICAL", "RCON is enabled with an empty password."));
            } else if (password.length() < 12) {
                findings.add(finding("WARNING", "RCON is enabled with a short password; use a long random secret."));
            } else {
                findings.add(finding("WARNING", "RCON is enabled. Restrict the port with a firewall and rotate the password."));
            }
        }
        if (Boolean.parseBoolean(props.getProperty("enable-query", "false"))) {
            findings.add(finding("WARNING", "Query is enabled. Restrict UDP 25565 to trusted networks if not needed."));
        }
        if (!Boolean.parseBoolean(props.getProperty("enforce-secure-profile", "true"))) {
            findings.add(finding("WARNING", "enforce-secure-profile=false weakens signed chat identity checks."));
        }
    }

    private boolean readBoolean(@NotNull File file, @NotNull String path) {
        if (!file.isFile()) return false;
        try {
            return YamlConfiguration.loadConfiguration(file).getBoolean(path, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Baseline every plugin JAR once. A changed JAR produces a warning and the
     * baseline is refreshed so the warning is emitted only once per change.
     */
    private void auditPluginJarBaseline(@NotNull List<Finding> findings) {
        if (!plugin.getConfig().getBoolean("security.jar-baseline", true)) return;
        File pluginDir = plugin.getDataFolder().getParentFile();
        if (pluginDir == null || !pluginDir.isDirectory()) return;
        File baselineFile = new File(plugin.getDataFolder(), "security-baseline.yml");
        YamlConfiguration baseline = baselineFile.isFile()
                ? YamlConfiguration.loadConfiguration(baselineFile)
                : new YamlConfiguration();
        boolean changed = false;
        File[] jars = pluginDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) return;
        Map<String, String> hashes = new TreeMap<>();
        for (File jar : jars) {
            String hash = jarHash(jar);
            if (hash == null) continue;
            hashes.put(jar.getName(), hash);
            String old = baseline.getString("jars." + jar.getName());
            if (old == null) {
                changed = true;
            } else if (!old.equals(hash)) {
                findings.add(finding("WARNING", "Plugin JAR changed since the last baseline: "
                        + jar.getName() + " (possible update, or tampering/infection; verify the source)."));
                changed = true;
            }
            baseline.set("jars." + jar.getName(), hash);
        }
        if (changed) {
            baseline.set("updated-at", System.currentTimeMillis());
            try {
                baseline.save(baselineFile);
            } catch (IOException ex) {
                findings.add(finding("WARNING", "Could not save security baseline: " + ex.getMessage()));
            }
        }
    }

    private void auditLuckPermsHolders(@NotNull List<Finding> findings) {
        PermissionModule permissions = plugin.getLib().getPermissionModule();
        if (!permissions.hasLuckPerms()) return;
        String defaultGroup = plugin.getConfig().getString("auto-blacklist.luckperms.default-group", "default");
        for (PermissionModule.HolderSnapshot holder : permissions.snapshotGroups(defaultGroup)) {
            boolean bypass = holder.grants("saryuantichatmanager.bypass")
                    || holder.grants("saryuantichatmanager.*") || holder.grants("*");
            if (bypass) {
                String level = holder.defaultGroup ? "CRITICAL" : "WARNING";
                findings.add(finding(level, "LuckPerms group '" + holder.name
                        + "' holds a bypass/wildcard permission. Review with /acm audit."));
            }
        }
    }

    private void auditExecutableFiles(@NotNull List<Finding> findings) {
        File pluginDir = plugin.getDataFolder().getParentFile();
        if (pluginDir == null || !pluginDir.isDirectory()) return;
        File[] files = pluginDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f == null || f.isDirectory()) continue;
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".exe") || name.endsWith(".dll") || name.endsWith(".so")
                    || name.endsWith(".sh") || name.endsWith(".bat") || name.endsWith(".bin")) {
                findings.add(finding("WARNING",
                        "Uncommon executable file in plugins/: " + f.getName() + " (review if unexpected)."));
            }
        }
    }

    private Finding finding(String level, String message) {
        Finding f = new Finding();
        f.level = level;
        f.message = message;
        return f;
    }

    private File selfJar() {
        try {
            java.net.URL url = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                File f = new File(url.toURI());
                if (f.isFile()) return f;
            }
        } catch (Exception ignored) {}
        try {
            Field field = JavaPlugin.class.getDeclaredField("file");
            field.setAccessible(true);
            Object obj = field.get(plugin);
            if (obj instanceof File file) return file;
        } catch (Exception ignored) {}
        return null;
    }

    private String jarHash(File file) {
        if (file == null || !file.exists()) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = fis.read(buf)) != -1) md.update(buf, 0, read);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
