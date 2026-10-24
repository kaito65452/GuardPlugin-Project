package id.saryu.saryuguard.filter;

import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.module.ModuleStatus;
import id.saryu.saryuguard.module.SaryuGuardModule;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * High-performance chat filter module.
 *
 * <p>Requirements:
 * - AsyncChatEvent (chat-filter.enabled)
 * - Normalization: lower, NFKC, diacritics, zero-width/RTL, homoglyphs, leet, collapse repeats.
 * - Layer 1: Aho-Corasick with word boundaries & Indonesian whitelist.
 * - Censored tokens (*): Trie search without regex, max 2 stars, min length, whitelist.
 * - Layer 2: Admin regex via RE2J only, validated, atomic swap.
 * - On violation: modify viewers() to offender-only view, send warning; do NOT cancel and resend.
 * - on-error: allow|block (default allow, logged).
 * - Target: < 2 ms per message on 100 players, rebuild < 500 ms.</p>
 */
public class ChatFilterModule implements SaryuGuardModule, Listener {

    private final SaryuGuardPlugin plugin;
    private final File wordsFile;

    private boolean enabled = true;
    private String onErrorAction = "allow"; // allow | block
    private boolean collapseRepeats = true;
    private boolean censorCharTrie = true;
    private int maxCensorChars = 2;
    private int minCensorWordLength = 4;
    private int maxRegexCount = 50;
    private int maxRegexLength = 128;

    private ModuleStatus status = ModuleStatus.DISABLED;
    private String statusMessage = "Disabled";

    private final Set<String> badWords = ConcurrentHashMap.newKeySet();
    private final Set<String> whitelistWords = ConcurrentHashMap.newKeySet();
    private final List<String> rawAdminRegex = new ArrayList<>();

    private final AtomicReference<AhoCorasick> ahoCorasickRef = new AtomicReference<>();
    private final AtomicReference<CensorTrie> censorTrieRef = new AtomicReference<>();
    private final AdminRegexMatcher adminRegexMatcher;

    private final PlainTextComponentSerializer plainSerializer = PlainTextComponentSerializer.plainText();

    public ChatFilterModule(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
        this.wordsFile = new File(plugin.getDataFolder(), "words.yml");
        this.adminRegexMatcher = new AdminRegexMatcher(plugin.getLogger());
    }

    @Override
    public @NotNull String id() {
        return "chat-filter";
    }

    @Override
    public @NotNull String displayName() {
        return "Chat Filter";
    }

    @Override
    public synchronized void enable() throws Exception {
        var cfg = plugin.getConfigManager().getConfig();
        this.enabled = cfg.getBoolean("chat-filter.enabled", true);
        this.onErrorAction = cfg.getString("chat-filter.on-error", "allow");
        this.collapseRepeats = cfg.getBoolean("chat-filter.collapse-repeats", true);
        this.censorCharTrie = cfg.getBoolean("chat-filter.censor-char-trie", true);
        this.maxCensorChars = cfg.getInt("chat-filter.max-censor-chars", 2);
        this.minCensorWordLength = cfg.getInt("chat-filter.min-censor-word-length", 4);
        this.maxRegexCount = cfg.getInt("chat-filter.max-regex-count", 50);
        this.maxRegexLength = cfg.getInt("chat-filter.max-regex-length", 128);

        if (!enabled) {
            this.status = ModuleStatus.DISABLED;
            this.statusMessage = "Disabled in config";
            return;
        }

        // Load words and build engines
        loadWordsConfig();
        buildEngines();

        // Register event listener
        Bukkit.getPluginManager().registerEvents(this, plugin);

        this.status = ModuleStatus.ACTIVE;
        this.statusMessage = "Active (" + badWords.size() + " words, " + adminRegexMatcher.getRuleCount() + " regex rules)";
        plugin.getLogger().info("[CHAT FILTER] Chat filter aktif dengan " + badWords.size() + " kata terlarang dan "
                + whitelistWords.size() + " kata whitelist.");
    }

    @Override
    public synchronized void disable() {
        HandlerList.unregisterAll(this);
        badWords.clear();
        whitelistWords.clear();
        rawAdminRegex.clear();
        ahoCorasickRef.set(null);
        censorTrieRef.set(null);
        this.status = ModuleStatus.DISABLED;
        this.statusMessage = "Disabled";
    }

    @Override
    public boolean isEnabled() {
        return enabled && status != ModuleStatus.DISABLED;
    }

    @Override
    public @NotNull ModuleStatus getStatus() {
        return status;
    }

    @Override
    public @NotNull String getStatusMessage() {
        return statusMessage;
    }

    private void loadWordsConfig() {
        if (!wordsFile.exists()) {
            saveDefaultWordsFile();
        }

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(wordsFile);
        List<String> words = cfg.getStringList("words");
        List<String> whitelist = cfg.getStringList("whitelist");
        List<String> regexes = cfg.getStringList("regex");

        badWords.clear();
        for (String w : words) {
            if (w != null && !w.isBlank()) badWords.add(w.trim().toLowerCase(Locale.ROOT));
        }

        whitelistWords.clear();
        for (String wl : whitelist) {
            if (wl != null && !wl.isBlank()) whitelistWords.add(wl.trim().toLowerCase(Locale.ROOT));
        }

        rawAdminRegex.clear();
        rawAdminRegex.addAll(regexes);
    }

    public synchronized void buildEngines() {
        long start = System.currentTimeMillis();

        // 1. Layer 1: Aho-Corasick
        AhoCorasick ac = new AhoCorasick(badWords, whitelistWords);
        ahoCorasickRef.set(ac);

        // 2. Censored Asterisk Trie
        if (censorCharTrie) {
            CensorTrie trie = new CensorTrie(badWords, whitelistWords, minCensorWordLength, maxCensorChars);
            censorTrieRef.set(trie);
        } else {
            censorTrieRef.set(null);
        }

        // 3. Layer 2: RE2J Admin Regex
        adminRegexMatcher.compileAndSet(rawAdminRegex, maxRegexCount, maxRegexLength);

        long elapsed = System.currentTimeMillis() - start;
        plugin.getLogger().info("[CHAT FILTER] Pembangunan ulang ruleset selesai dalam " + elapsed + " ms.");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        // Bypass check
        if (player.hasPermission("saryuguard.words.bypass")) {
            return;
        }

        String rawMessage;
        try {
            rawMessage = plainSerializer.serialize(event.message());
        } catch (Throwable t) {
            return;
        }

        if (rawMessage.isBlank()) return;

        try {
            FilterResult result = checkMessage(rawMessage);
            if (result.isBlocked) {
                // Sembunyikan pesan: Pesan pelanggar HANYA terlihat oleh pelanggar sendiri
                event.viewers().removeIf(viewer -> viewer != player);

                // Kirim peringatan ke pemain
                plugin.getLocaleManager().send(player, "chat.filtered-notice",
                        "<red>Pesan Anda disembunyikan dari pemain lain karena melanggar aturan obrolan (<white>{reason}</white>).",
                        "reason", result.reason);

                plugin.getLogger().info("[CHAT FILTER] Pesan dari " + player.getName()
                        + " disembunyikan karena memicu: " + result.matchedKeyword);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "[CHAT FILTER ERROR] Terjadi kegagalan saat memeriksa chat: " + t.getMessage(), t);

            if ("block".equalsIgnoreCase(onErrorAction)) {
                event.setCancelled(true);
                plugin.getLocaleManager().send(player, "chat.error-blocked",
                        "<red>Pesan Anda dibatalkan sementara karena error pada sistem filter obrolan.");
            }
            // If "allow" (default), message passes through and error is logged.
        }
    }

    public static class FilterResult {
        public final boolean isBlocked;
        public final String reason;
        public final String matchedKeyword;

        public FilterResult(boolean isBlocked, String reason, String matchedKeyword) {
            this.isBlocked = isBlocked;
            this.reason = reason;
            this.matchedKeyword = matchedKeyword;
        }

        public static FilterResult clean() {
            return new FilterResult(false, "CLEAN", null);
        }

        public static FilterResult violation(String reason, String matchedKeyword) {
            return new FilterResult(true, reason, matchedKeyword);
        }
    }

    /**
     * Inspects a raw text message against normalization and filtering layers.
     */
    public @NotNull FilterResult checkMessage(@NotNull String message) {
        // 1. Text Normalization Pipeline
        String normalized = TextNormalizer.normalize(message, collapseRepeats);

        // 2. Layer 1: Aho-Corasick Automaton with word boundaries & whitelist
        AhoCorasick ac = ahoCorasickRef.get();
        if (ac != null) {
            List<AhoCorasick.Match> matches = ac.search(normalized);
            if (!matches.isEmpty()) {
                AhoCorasick.Match first = matches.get(0);
                return FilterResult.violation("Kata Terlarang", first.keyword);
            }
        }

        // 3. Censored Character Trie ('*')
        CensorTrie trie = censorTrieRef.get();
        if (trie != null && message.contains("*")) {
            // Split normalized tokens
            String[] tokens = normalized.split("\\s+");
            for (String token : tokens) {
                if (token.contains("*")) {
                    String matchedWord = trie.checkToken(token);
                    if (matchedWord != null) {
                        return FilterResult.violation("Kata Sensor", matchedWord);
                    }
                }
            }
        }

        // 4. Layer 2: Admin RE2J Regular Expressions
        if (adminRegexMatcher.matches(normalized) || adminRegexMatcher.matches(message)) {
            return FilterResult.violation("Pola Regex Admin", "REGEX_MATCH");
        }

        return FilterResult.clean();
    }

    public synchronized boolean addWord(@NotNull String word) {
        String clean = word.trim().toLowerCase(Locale.ROOT);
        if (badWords.add(clean)) {
            saveWordsFileAsync();
            buildEngines();
            return true;
        }
        return false;
    }

    public synchronized boolean removeWord(@NotNull String word) {
        String clean = word.trim().toLowerCase(Locale.ROOT);
        if (badWords.remove(clean)) {
            saveWordsFileAsync();
            buildEngines();
            return true;
        }
        return false;
    }

    public @NotNull Set<String> getBadWords() {
        return Collections.unmodifiableSet(new HashSet<>(badWords));
    }

    private void saveWordsFileAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                FileConfiguration cfg = new YamlConfiguration();
                cfg.set("words", new ArrayList<>(badWords));
                cfg.set("whitelist", new ArrayList<>(whitelistWords));
                cfg.set("regex", rawAdminRegex);
                cfg.save(wordsFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save words.yml: " + e.getMessage(), e);
            }
        });
    }

    private void saveDefaultWordsFile() {
        try {
            FileConfiguration cfg = new YamlConfiguration();
            // Default Indonesian and common offensive terms
            List<String> defaultWords = List.of(
                    "anjing", "bangsat", "kontol", "memek", "bajingan",
                    "babi", "jembut", "pantek", "perek", "ngentot", "itil", "puki"
            );
            // Default Indonesian whitelist words that should never trigger false positives
            List<String> defaultWhitelist = List.of(
                    "membahas", "masyarakat", "bimbingan", "mangga", "satu", "kucing",
                    "beritahu", "pertahanan", "tanggung", "terhormat", "koleksi",
                    "kontes", "kontrak", "kontribusi", "konteks", "pemberitahuan"
            );
            // Default admin regexes
            List<String> defaultRegex = List.of(
                    "discord\\.(gg|io|me)/[a-zA-Z0-9]+",
                    "(\\d{1,3}\\.){3}\\d{1,3}:\\d{4,5}"
            );

            cfg.set("words", defaultWords);
            cfg.set("whitelist", defaultWhitelist);
            cfg.set("regex", defaultRegex);
            cfg.save(wordsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not create default words.yml: " + e.getMessage());
        }
    }
}
