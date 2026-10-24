package id.saryu.saryuguard.opguard;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Multi-source local name → UUID resolver for OpGuard.
 *
 * <p>Never contacts Mojang or calls {@code Bukkit.getOfflinePlayer(String)}
 * (which triggers a blocking HTTP call on Spigot/Paper).</p>
 *
 * <p>Sources (each can be disabled in config):
 * <ol>
 *   <li>LuckPerms: {@code UserManager.lookupUniqueId(name)} (async)</li>
 *   <li>EssentialsX: {@code User} lookup via the Essentials API, fallback to userdata files</li>
 *   <li>ops.json: parsed read-only from the server root</li>
 *   <li>Bukkit.getOfflinePlayerIfCached (optional, Paper API)</li>
 * </ol>
 *
 * <p><b>Consensus rules:</b> all sources agree → proceed. Single source → proceed
 * with note. Different UUIDs → REJECT with conflict details. No source found → suggest
 * raw UUID. Failed reading source → ERROR status.</p>
 */
public class NameResolver {

    /** Maximum ops.json size to parse (10 MB). */
    public static final long MAX_OPS_JSON_BYTES = 10 * 1024 * 1024;

    /** Valid Minecraft name pattern (Java edition). */
    private static final Pattern JAVA_NAME = Pattern.compile("^[a-zA-Z0-9_]{1,16}$");

    /** Source lookup functional interface. */
    @FunctionalInterface
    public interface ResolverSource {
        @Nullable UUID lookup(@NotNull String name) throws Exception;
    }

    private final Logger log;
    private final Path serverRoot;

    // Config toggles
    private volatile boolean useLuckPerms = true;
    private volatile boolean useEssentials = true;
    private volatile boolean useOpsJson = true;
    private volatile boolean useBukkitCache = true;
    private volatile boolean allowBedrockPrefix = false;
    private volatile String bedrockPrefix = ".";

    // Custom/override sources for testing or extension
    private final Map<String, ResolverSource> customSources = new LinkedHashMap<>();

    public NameResolver(@Nullable Logger log, @NotNull Path serverRoot) {
        this.log = log;
        this.serverRoot = serverRoot;
    }

    /**
     * Load resolver source toggles from the plugin configuration.
     */
    public void loadConfig(@NotNull FileConfiguration cfg) {
        useLuckPerms = cfg.getBoolean("opguard.resolve.sources.luckperms", true);
        useEssentials = cfg.getBoolean("opguard.resolve.sources.essentials", true);
        useOpsJson = cfg.getBoolean("opguard.resolve.sources.ops-json", true);
        useBukkitCache = cfg.getBoolean("opguard.resolve.sources.bukkit-cache", true);
        allowBedrockPrefix = cfg.getBoolean("opguard.resolve.bedrock-prefix.enabled", false);
        bedrockPrefix = cfg.getString("opguard.resolve.bedrock-prefix.prefix", ".");
    }

    // -------------------------------------------------------------------------
    // Testing and source extension
    // -------------------------------------------------------------------------

    public void registerSource(@NotNull String name, @NotNull ResolverSource source) {
        customSources.put(name, source);
    }

    public void unregisterSource(@NotNull String name) {
        customSources.remove(name);
    }

    public void clearCustomSources() {
        customSources.clear();
    }

    public void setUseLuckPerms(boolean useLuckPerms) { this.useLuckPerms = useLuckPerms; }
    public void setUseEssentials(boolean useEssentials) { this.useEssentials = useEssentials; }
    public void setUseOpsJson(boolean useOpsJson) { this.useOpsJson = useOpsJson; }
    public void setUseBukkitCache(boolean useBukkitCache) { this.useBukkitCache = useBukkitCache; }
    public void setAllowBedrockPrefix(boolean allow) { this.allowBedrockPrefix = allow; }
    public void setBedrockPrefix(String prefix) { this.bedrockPrefix = prefix; }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Validate a player name. Returns null if valid, or an error message.
     */
    public @Nullable String validateName(@NotNull String name) {
        if (name.isEmpty()) return "Nama kosong.";
        if (name.length() > 16) return "Nama terlalu panjang (maks 16 karakter).";

        String checkName = name;
        if (allowBedrockPrefix && bedrockPrefix != null && !bedrockPrefix.isEmpty()
                && name.startsWith(bedrockPrefix)) {
            checkName = name.substring(bedrockPrefix.length());
            if (checkName.isEmpty()) return "Nama Bedrock tidak valid (hanya awalan).";
        }
        if (!JAVA_NAME.matcher(checkName).matches()) {
            return "Nama hanya boleh mengandung huruf, angka, dan underscore (_).";
        }
        return null;
    }

    /**
     * Try to parse a string as UUID directly.
     */
    public static @Nullable UUID tryParseUuid(@NotNull String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        try {
            if (trimmed.length() == 32) {
                // Add dashes: 8-4-4-4-12
                String dashed = trimmed.substring(0, 8) + "-" + trimmed.substring(8, 12) + "-"
                        + trimmed.substring(12, 16) + "-" + trimmed.substring(16, 20) + "-"
                        + trimmed.substring(20);
                return UUID.fromString(dashed);
            }
            if (trimmed.length() == 36) {
                return UUID.fromString(trimmed);
            }
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Resolution
    // -------------------------------------------------------------------------

    /**
     * Result of a multi-source name resolution.
     */
    public record ResolveResult(
            @NotNull Status status,
            @Nullable UUID uuid,
            @Nullable String resolvedName,
            @NotNull Map<String, UUID> sourceResults,
            @Nullable String message
    ) {
        public enum Status { OK, CONFLICT, NOT_FOUND, ERROR }

        public static ResolveResult ok(@NotNull UUID uuid, @NotNull String name,
                                       @NotNull Map<String, UUID> sources, @Nullable String note) {
            return new ResolveResult(Status.OK, uuid, name, sources,
                    note != null ? note : "Resolusi berhasil.");
        }

        public static ResolveResult conflict(@NotNull Map<String, UUID> sources) {
            StringBuilder sb = new StringBuilder("Konflik UUID antar sumber: ");
            sources.forEach((src, id) -> sb.append(src).append("=").append(id).append(", "));
            return new ResolveResult(Status.CONFLICT, null, null, sources, sb.toString());
        }

        public static ResolveResult notFound(@NotNull String name) {
            return new ResolveResult(Status.NOT_FOUND, null, name, Map.of(),
                    "Nama '" + name + "' tidak ditemukan di sumber mana pun. "
                            + "Gunakan /sg opguard add <uuid> untuk menambahkan langsung.");
        }

        public static ResolveResult error(@NotNull String message) {
            return new ResolveResult(Status.ERROR, null, null, Map.of(), message);
        }
    }

    /**
     * Resolve a name to UUID using all enabled sources.
     * Evaluates consensus rules strictly:
     * - Any source error (e.g. ops.json unreadable/corrupt) -> ERROR
     * - No sources found -> NOT_FOUND
     * - All agreeing or single source -> OK (with note if single)
     * - Differing UUIDs -> CONFLICT
     */
    public @NotNull ResolveResult resolve(@NotNull String name) {
        Map<String, UUID> results = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();

        // If custom sources are registered (e.g. in tests)
        if (!customSources.isEmpty()) {
            for (Map.Entry<String, ResolverSource> entry : customSources.entrySet()) {
                try {
                    UUID u = entry.getValue().lookup(name);
                    if (u != null) results.put(entry.getKey(), u);
                } catch (Exception e) {
                    errors.put(entry.getKey(), e.getMessage());
                }
            }
        } else {
            // Standard built-in sources

            // 1. LuckPerms
            if (useLuckPerms) {
                try {
                    UUID lp = lookupLuckPerms(name);
                    if (lp != null) results.put("LuckPerms", lp);
                } catch (Exception e) {
                    errors.put("LuckPerms", e.getMessage());
                    if (log != null) log.log(Level.FINE, "[OpGuard] LuckPerms lookup error for " + name, e);
                }
            }

            // 2. EssentialsX
            if (useEssentials) {
                try {
                    UUID ess = lookupEssentials(name);
                    if (ess != null) results.put("EssentialsX", ess);
                } catch (Exception e) {
                    errors.put("EssentialsX", e.getMessage());
                    if (log != null) log.log(Level.FINE, "[OpGuard] EssentialsX lookup error for " + name, e);
                }
            }

            // 3. ops.json
            if (useOpsJson) {
                try {
                    UUID ops = lookupOpsJson(name);
                    if (ops != null) results.put("ops.json", ops);
                } catch (Exception e) {
                    errors.put("ops.json", e.getMessage());
                    if (log != null) log.log(Level.FINE, "[OpGuard] ops.json lookup error for " + name, e);
                }
            }

            // 4. Bukkit cache
            if (useBukkitCache) {
                try {
                    UUID cached = lookupBukkitCache(name);
                    if (cached != null) results.put("Bukkit-cache", cached);
                } catch (Exception e) {
                    errors.put("Bukkit-cache", e.getMessage());
                    if (log != null) log.log(Level.FINE, "[OpGuard] Bukkit-cache lookup error for " + name, e);
                }
            }
        }

        // Rule: Gagal membaca sumber (ops.json hilang/rusak) = status ERROR untuk resolusi itu
        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Gagal membaca sumber: ");
            errors.forEach((src, msg) -> sb.append(src).append(" (").append(msg).append("); "));
            return ResolveResult.error(sb.toString().trim());
        }

        // Rule: Tidak ketemu di sumber mana pun: sarankan add <uuid> dan jangan menebak
        if (results.isEmpty()) {
            return ResolveResult.notFound(name);
        }

        // Rule: Tanyakan SEMUA sumber yang aktif, lalu bandingkan.
        Set<UUID> uniqueUuids = new HashSet<>(results.values());
        if (uniqueUuids.size() == 1) {
            UUID agreed = uniqueUuids.iterator().next();
            String note = null;
            if (results.size() == 1) {
                String singleSource = results.keySet().iterator().next();
                note = "Hanya ditemukan di satu sumber: " + singleSource + ".";
            }
            return ResolveResult.ok(agreed, name, results, note);
        }

        // Rule: Hasil berbeda antar sumber = TOLAK dan tampilkan konfliknya
        return ResolveResult.conflict(results);
    }

    // -------------------------------------------------------------------------
    // Source: LuckPerms
    // -------------------------------------------------------------------------

    private @Nullable UUID lookupLuckPerms(@NotNull String name) {
        try {
            Plugin lp = Bukkit.getPluginManager().getPlugin("LuckPerms");
            if (lp == null || !lp.isEnabled()) return null;

            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object api = providerClass.getMethod("get").invoke(null);
            Object userManager = api.getClass().getMethod("getUserManager").invoke(api);

            @SuppressWarnings("unchecked")
            CompletableFuture<UUID> future = (CompletableFuture<UUID>)
                    userManager.getClass().getMethod("lookupUniqueId", String.class)
                            .invoke(userManager, name);

            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (java.util.concurrent.TimeoutException e) {
            if (log != null) log.warning("[OpGuard] LuckPerms lookup timeout for: " + name);
            return null;
        } catch (Exception e) {
            if (e.getCause() instanceof ClassNotFoundException) return null;
            throw new RuntimeException("LuckPerms lookup error: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Source: EssentialsX
    // -------------------------------------------------------------------------

    private @Nullable UUID lookupEssentials(@NotNull String name) {
        try {
            Plugin essPlugin = Bukkit.getPluginManager().getPlugin("Essentials");
            if (essPlugin == null || !essPlugin.isEnabled()) return null;

            try {
                Object user = essPlugin.getClass().getMethod("getUser", String.class)
                        .invoke(essPlugin, name);
                if (user != null) {
                    try {
                        Object base = user.getClass().getMethod("getBase").invoke(user);
                        if (base != null) {
                            return (UUID) base.getClass().getMethod("getUniqueId").invoke(base);
                        }
                    } catch (Exception ignored) {}

                    try {
                        return (UUID) user.getClass().getMethod("getConfigUUID").invoke(user);
                    } catch (Exception ignored) {}
                }
            } catch (NoSuchMethodException ignored) {}

            return lookupEssentialsFiles(name, essPlugin);
        } catch (Exception e) {
            throw new RuntimeException("EssentialsX lookup error: " + e.getMessage(), e);
        }
    }

    private @Nullable UUID lookupEssentialsFiles(@NotNull String name, @NotNull Plugin essPlugin) {
        Path userdata = essPlugin.getDataFolder().toPath().resolve("userdata");
        if (!Files.isDirectory(userdata)) return null;

        String lowerName = name.toLowerCase(Locale.ROOT);
        try (var stream = Files.newDirectoryStream(userdata, "*.yml")) {
            for (Path file : stream) {
                if (Files.size(file) > MAX_OPS_JSON_BYTES) continue;

                String fileName = file.getFileName().toString();
                String uuidStr = fileName.substring(0, fileName.length() - 4);
                UUID fileUuid = tryParseUuid(uuidStr);
                if (fileUuid == null) continue;

                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (String line : lines) {
                        if (lines.indexOf(line) > 30) break;
                        String trimmed = line.trim();
                        if (trimmed.startsWith("last-account-name:") || trimmed.startsWith("lastAccountName:")) {
                            String val = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                            if (val.startsWith("'") && val.endsWith("'")) val = val.substring(1, val.length() - 1);
                            if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
                            if (val.equalsIgnoreCase(lowerName)) {
                                return fileUuid;
                            }
                            break;
                        }
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            if (log != null) log.log(Level.FINE, "[OpGuard] Error reading Essentials userdata", e);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Source: ops.json
    // -------------------------------------------------------------------------

    public @Nullable UUID lookupOpsJson(@NotNull String name) throws Exception {
        Path opsPath = serverRoot.resolve("ops.json");
        if (!Files.exists(opsPath)) {
            throw new FileNotFoundException("File ops.json tidak ditemukan (hilang).");
        }

        long size = Files.size(opsPath);
        if (size > MAX_OPS_JSON_BYTES) {
            throw new IOException("File ops.json melebihi batas ukuran " + MAX_OPS_JSON_BYTES + " bytes (sangat besar).");
        }
        if (size == 0) return null;

        String content = Files.readString(opsPath, StandardCharsets.UTF_8).trim();
        if (!content.startsWith("[") || !content.endsWith("]")) {
            throw new IllegalArgumentException("File ops.json rusak (bukan format JSON array).");
        }
        return parseOpsJsonForName(content, name);
    }

    /**
     * Parse ops.json entries ignoring corrupt individual entries.
     */
    public @Nullable UUID parseOpsJsonForName(@NotNull String json, @NotNull String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        int idx = 0;
        while (idx < json.length()) {
            int objStart = json.indexOf('{', idx);
            if (objStart < 0) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;

            String block = json.substring(objStart, objEnd + 1);
            int uuidKeyIdx = block.indexOf("\"uuid\"");
            int nameKeyIdx = block.indexOf("\"name\"");

            if (uuidKeyIdx >= 0 && nameKeyIdx >= 0) {
                String entryName = extractJsonStringValue(block, nameKeyIdx);
                String uuidStr = extractJsonStringValue(block, uuidKeyIdx);
                if (entryName != null && entryName.equalsIgnoreCase(lowerName)) {
                    if (uuidStr != null) {
                        UUID parsed = tryParseUuid(uuidStr);
                        if (parsed != null) return parsed;
                        // Corrupt UUID in individual entry is ignored
                    }
                }
            }
            idx = objEnd + 1;
        }
        return null;
    }

    /**
     * Parse all valid entries from ops.json content, ignoring corrupt ones.
     */
    public @NotNull List<OpsJsonEntry> parseAllOpsJson(@NotNull String json) {
        List<OpsJsonEntry> result = new ArrayList<>();
        int idx = 0;
        while (idx < json.length()) {
            int objStart = json.indexOf('{', idx);
            if (objStart < 0) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;

            String block = json.substring(objStart, objEnd + 1);
            int uuidKeyIdx = block.indexOf("\"uuid\"");
            int nameKeyIdx = block.indexOf("\"name\"");

            if (uuidKeyIdx >= 0) {
                String uuidStr = extractJsonStringValue(block, uuidKeyIdx);
                String entryName = nameKeyIdx >= 0 ? extractJsonStringValue(block, nameKeyIdx) : null;
                if (uuidStr != null) {
                    UUID parsed = tryParseUuid(uuidStr);
                    if (parsed != null) {
                        result.add(new OpsJsonEntry(parsed, entryName));
                    }
                }
            }
            idx = objEnd + 1;
        }
        return result;
    }

    /**
     * Get names from ops.json for tab completion.
     */
    public @NotNull List<String> getOpsJsonNames() {
        Path opsPath = serverRoot.resolve("ops.json");
        if (!Files.exists(opsPath)) return List.of();
        try {
            long size = Files.size(opsPath);
            if (size > MAX_OPS_JSON_BYTES || size == 0) return List.of();
            String content = Files.readString(opsPath, StandardCharsets.UTF_8).trim();
            if (!content.startsWith("[") || !content.endsWith("]")) return List.of();
            List<OpsJsonEntry> entries = parseAllOpsJson(content);
            List<String> names = new ArrayList<>();
            for (OpsJsonEntry e : entries) {
                if (e.name() != null) names.add(e.name());
            }
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Read ops.json content for import. Throws exception if missing, oversized, or corrupt.
     */
    public @NotNull String readOpsJson() throws Exception {
        Path opsPath = serverRoot.resolve("ops.json");
        if (!Files.exists(opsPath)) {
            throw new FileNotFoundException("File ops.json tidak ditemukan (hilang).");
        }
        long size = Files.size(opsPath);
        if (size > MAX_OPS_JSON_BYTES) {
            throw new IOException("File ops.json melebihi batas ukuran maksimum (" + size + " bytes).");
        }
        if (size == 0) return "[]";
        String content = Files.readString(opsPath, StandardCharsets.UTF_8).trim();
        if (!content.startsWith("[") || !content.endsWith("]")) {
            throw new IllegalArgumentException("File ops.json rusak (format JSON array tidak valid).");
        }
        return content;
    }

    public record OpsJsonEntry(@NotNull UUID uuid, @Nullable String name) {}

    // -------------------------------------------------------------------------
    // Source: Bukkit cache
    // -------------------------------------------------------------------------

    private @Nullable UUID lookupBukkitCache(@NotNull String name) {
        try {
            OfflinePlayer p = Bukkit.getOfflinePlayerIfCached(name);
            return p != null ? p.getUniqueId() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // JSON helpers (minimal, no external library)
    // -------------------------------------------------------------------------

    private static @Nullable String extractJsonStringValue(@NotNull String json, int keyStart) {
        int colonIdx = json.indexOf(':', keyStart);
        if (colonIdx < 0) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }
}
