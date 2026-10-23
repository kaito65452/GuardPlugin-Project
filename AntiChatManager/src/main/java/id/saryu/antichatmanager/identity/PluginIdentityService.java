package id.saryu.antichatmanager.identity;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.libcore.repository.RepositoryClient;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class PluginIdentityService {

    public static class Identity {
        public String pluginName;
        public String jarHashSha1;
        public String jarHashSha512;
        public String source;
        public String projectSlug;
        public String projectTitle;
        public String categories;
        public String projectVersion;
        public boolean verified;
        public int confidence;
        public long expiresAt;
    }

    private final AntiChatManager plugin;
    private final Map<String, Identity> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private boolean enabled = true;
    private String userAgent = "Saryu/AntiChatManager/{VERSION} (contact-admin)";
    private long cacheTtlMs = 7L * 24L * 60L * 60L * 1000L;
    private boolean hangarFallback = true;
    private boolean sendOnlyHashes = true;
    private int minConfidence = 70;

    public PluginIdentityService(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        enabled = plugin.getConfigModule().getBoolean("plugin-identity.enabled", true);
        userAgent = plugin.getConfigModule().getString("plugin-identity.modrinth.user-agent",
                "Saryu/AntiChatManager/{VERSION} (contact-admin)");
        userAgent = userAgent.replace("{VERSION}", plugin.getBuildInfo().getVersion());
        String ttl = plugin.getConfigModule().getString("plugin-identity.modrinth.cache-ttl", "7d");
        cacheTtlMs = parseTtl(ttl);
        hangarFallback = plugin.getConfigModule().getBoolean("plugin-identity.modrinth.hangar-fallback", true);
        sendOnlyHashes = plugin.getConfigModule().getBoolean("plugin-identity.modrinth.send-only-hashes", true);
        minConfidence = plugin.getConfigModule().getInt("plugin-identity.min-confidence", 70);
    }

    private long parseTtl(String ttl) {
        if (ttl == null) return 7L * 24L * 60L * 60L * 1000L;
        String s = ttl.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("d")) return Long.parseLong(s.replace("d", "")) * 24L * 60L * 60L * 1000L;
            if (s.endsWith("h")) return Long.parseLong(s.replace("h", "")) * 60L * 60L * 1000L;
        } catch (Exception ignored) {}
        return 7L * 24L * 60L * 60L * 1000L;
    }

    public void start() {
        reloadConfig();
        if (!enabled) return;
        if (!running.compareAndSet(false, true)) return;
        plugin.getLib().getSchedulerModule().runSyncLater(this::identifyAll, 100L);
    }

    public void stop() {
        running.set(false);
    }

    public void identifyNow() {
        if (!enabled) return;
        identifyAll();
    }

    private void identifyAll() {
        if (!enabled) return;
        List<Plugin> plugins = java.util.Arrays.asList(Bukkit.getPluginManager().getPlugins());
        for (Plugin p : plugins) {
            if (p == null) continue;
            String name = p.getName();
            if (name.equals(plugin.getName()) || name.equals("SaryuLibCore")) continue;
            Identity cached = cache.get(name);
            if (cached != null && cached.expiresAt > System.currentTimeMillis()) continue;
            identifyPlugin(p);
        }
    }

    private void identifyPlugin(@NotNull Plugin p) {
        // Hashing a JAR can take a moment for large plugins; never do it on the
        // server thread.
        plugin.getLib().getSchedulerModule().runAsync(() -> {
            File file = getPluginFile(p);
            if (file == null || !file.exists()) return;
            String sha1 = sha(file, "SHA-1");
            String sha512 = sha(file, "SHA-512");
            if (sha1 == null) return;
            lookupModrinth(p.getName(), sha1, sha512, file);
        });
    }

    private void lookupModrinth(String pluginName, String sha1, String sha512, File file) {
        RepositoryClient client = plugin.getLib().getRepositoryClient();
        String body = "{\"hashes\":[\"" + sha1 + "\"],\"algorithm\":\"sha1\"}";
        client.post("https://api.modrinth.com/v2/version_files", userAgent, body)
                .thenAccept(resp -> {
                    try {
                        parseModrinth(pluginName, sha1, sha512, resp);
                    } catch (Exception ex) {
                        if (hangarFallback) lookupHangar(pluginName, sha1, sha512, file);
                        else storeUnknown(pluginName, sha1, sha512);
                    }
                })
                .exceptionally(ex -> {
                    if (hangarFallback) lookupHangar(pluginName, sha1, sha512, file);
                    else storeUnknown(pluginName, sha1, sha512);
                    return null;
                });
    }

    private void lookupHangar(String pluginName, String sha1, String sha512, File file) {
        // Hangar does not always expose a public hash-lookup endpoint in the
        // same form; treat as unverified but mark that fallback was attempted.
        storeUnknown(pluginName, sha1, sha512);
    }

    private void parseModrinth(String pluginName, String sha1, String sha512, String resp) {
        // Minimal JSON extraction without adding a new dependency.
        String projectId = extract(resp, "\"project_id\":\"");
        String versionId = extract(resp, "\"id\":\"");
        String versionNum = extract(resp, "\"version_number\":\"");
        if (projectId == null) {
            storeUnknown(pluginName, sha1, sha512);
            return;
        }
        Identity id = new Identity();
        id.pluginName = pluginName;
        id.jarHashSha1 = sha1;
        id.jarHashSha512 = sha512;
        id.source = "MODRINTH";
        id.projectSlug = projectId;
        id.projectTitle = projectId;
        id.categories = "";
        id.projectVersion = versionNum == null ? "" : versionNum;
        id.verified = true;
        id.confidence = 95;
        id.expiresAt = System.currentTimeMillis() + cacheTtlMs;
        cache.put(pluginName, id);
        storeIdentity(id);
        plugin.getLib().getSchedulerModule().runSync(() ->
                plugin.getLogger().info("Identified " + pluginName + " via Modrinth (hash match)."));
    }

    private void storeUnknown(String pluginName, String sha1, String sha512) {
        Identity id = new Identity();
        id.pluginName = pluginName;
        id.jarHashSha1 = sha1;
        id.jarHashSha512 = sha512;
        id.source = "UNVERIFIED";
        id.projectSlug = null;
        id.projectTitle = null;
        id.categories = null;
        id.projectVersion = null;
        id.verified = false;
        id.confidence = 0;
        id.expiresAt = System.currentTimeMillis() + cacheTtlMs;
        cache.put(pluginName, id);
        storeIdentity(id);
    }

    private void storeIdentity(Identity id) {
        plugin.getStorage().savePluginIdentity(id);
    }

    private @Nullable String extract(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private @Nullable File getPluginFile(Plugin p) {
        try {
            java.lang.reflect.Field field = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("file");
            field.setAccessible(true);
            Object f = field.get(p);
            if (f instanceof File file) return file;
        } catch (Exception ignored) {}
        return null;
    }

    private @Nullable String sha(File file, String algo) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance(algo);
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

    public @Nullable Identity getIdentity(String pluginName) {
        return cache.get(pluginName);
    }

    public @NotNull Map<String, Identity> getAll() {
        return new LinkedHashMap<>(cache);
    }

    public int getMinConfidence() {
        return minConfidence;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
