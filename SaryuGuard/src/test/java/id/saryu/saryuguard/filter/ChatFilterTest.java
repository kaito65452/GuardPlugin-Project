package id.saryu.saryuguard.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ChatFilterTest {

    private static final Set<String> BAD_WORDS = Set.of(
            "anjing", "bangsat", "kontol", "memek", "bajingan",
            "babi", "jembut", "pantek", "perek", "ngentot", "itil", "puki"
    );

    private static final Set<String> WHITELIST = Set.of(
            "membahas", "masyarakat", "bimbingan", "mangga", "satu", "kucing",
            "beritahu", "pertahanan", "tanggung", "terhormat", "koleksi",
            "kontes", "kontrak", "kontribusi", "konteks", "pemberitahuan"
    );

    @Test
    @DisplayName("Censored character trie detects tokens with asterisk (b*ngo, an*ing, k*nt*l)")
    void testCensoredAsteriskTrie() {
        CensorTrie trie = new CensorTrie(BAD_WORDS, WHITELIST, 4, 2);

        // 1 asterisk match
        assertEquals("anjing", trie.checkToken("an*ing"));
        assertEquals("anjing", trie.checkToken("anj*ng"));
        assertEquals("bangsat", trie.checkToken("b*ngsat"));
        assertEquals("kontol", trie.checkToken("k*ntol"));

        // 2 asterisks match
        assertEquals("anjing", trie.checkToken("an**ng"));
        assertEquals("kontol", trie.checkToken("k*nt*l"));

        // > 2 asterisks rejected
        assertNull(trie.checkToken("a***ng"));

        // Shorter than min length (< 4) rejected
        assertNull(trie.checkToken("a*b"));

        // Whitelisted token rejected
        assertNull(trie.checkToken("kont*ks"));
    }

    @Test
    @DisplayName("Indonesian legitimate words must NOT be flagged as false positives")
    void testIndonesianFalsePositiveWhitelist() {
        AhoCorasick ac = new AhoCorasick(BAD_WORDS, WHITELIST);

        // All legitimate Indonesian words must be CLEAN
        for (String cleanWord : WHITELIST) {
            List<AhoCorasick.Match> matches = ac.search(cleanWord);
            assertTrue(matches.isEmpty(), "False positive triggered on valid Indonesian word: " + cleanWord);
        }

        // Test in sentence context
        List<String> validSentences = List.of(
                "mari kita membahas tentang konteks acara",
                "masyarakat satu tujuan mengikuti kontes",
                "kami meminta bimbingan dan pertahanan yang terhormat",
                "kucing makan mangga di rumah",
                "harap beritahu tentang kontrak kerja"
        );

        for (String sentence : validSentences) {
            String norm = TextNormalizer.normalize(sentence, true);
            List<AhoCorasick.Match> matches = ac.search(norm);
            assertTrue(matches.isEmpty(), "False positive triggered on valid sentence: " + sentence);
        }

        // Explicit profanity must still be flagged
        List<AhoCorasick.Match> badMatches = ac.search(TextNormalizer.normalize("kamu anjing banget", true));
        assertFalse(badMatches.isEmpty(), "Should detect offensive word");
        assertEquals("anjing", badMatches.get(0).keyword);
    }

    @Test
    @DisplayName("Layer 2: Admin RE2J regex compiles, validates, and matches")
    void testAdminRegexRe2j() {
        AdminRegexMatcher matcher = new AdminRegexMatcher(null);

        List<String> patterns = List.of(
                "discord\\.(gg|io|me)/[a-zA-Z0-9]+",
                "(\\d{1,3}\\.){3}\\d{1,3}:\\d{4,5}"
        );

        assertTrue(matcher.compileAndSet(patterns, 50, 128));
        assertEquals(2, matcher.getRuleCount());

        assertTrue(matcher.matches("join my server discord.gg/abc1234"));
        assertTrue(matcher.matches("connect to 192.168.1.10:25565 now"));
        assertFalse(matcher.matches("halo semua selamat pagi"));

        // Rejects invalid regex and keeps previous ruleset
        List<String> invalidPatterns = List.of("(?=lookahead)"); // RE2J does not support lookaheads
        assertFalse(matcher.compileAndSet(invalidPatterns, 50, 128));
        assertEquals(2, matcher.getRuleCount(), "Old ruleset must be retained upon failure");
    }

    @Test
    @DisplayName("Performance benchmark: < 2 ms per message across 100 players, rebuild < 500 ms")
    void testPerformanceBenchmark() throws Exception {
        // 1. Benchmark engine rebuild time (< 500 ms)
        long rebuildStart = System.nanoTime();
        AhoCorasick ac = new AhoCorasick(BAD_WORDS, WHITELIST);
        CensorTrie trie = new CensorTrie(BAD_WORDS, WHITELIST, 4, 2);
        AdminRegexMatcher re2j = new AdminRegexMatcher(null);
        re2j.compileAndSet(List.of("discord\\.(gg|io)/[a-z]+", "(\\d{1,3}\\.){3}\\d{1,3}"), 50, 128);
        long rebuildTimeMs = (System.nanoTime() - rebuildStart) / 1_000_000;

        assertTrue(rebuildTimeMs < 500, "Engine rebuild took " + rebuildTimeMs + " ms (must be < 500 ms)");

        // 2. Benchmark message processing under 100 players (< 2 ms per message)
        int numPlayers = 100;
        int messagesPerPlayer = 20; // 2000 total messages
        ExecutorService pool = Executors.newFixedThreadPool(16);

        List<String> sampleMessages = List.of(
                "Halo semuanya! Selamat datang di server.",
                "Kamu mau pergi ke mana hari ini?",
                "Tolong beritahu tentang kontrak dan kontes besok ya.",
                "Hey anj1ng b@ng547 lu!",
                "Join voice discord.gg/superpro sekarang",
                "kucing lagi tidur di teras rumah",
                "siapa yang punya k*nt*l di sini?",
                "bisa bantu bimbingan pertahanan desa?"
        );

        List<Callable<Long>> tasks = new ArrayList<>();
        for (int p = 0; p < numPlayers; p++) {
            tasks.add(() -> {
                long playerDurationNano = 0;
                for (int m = 0; m < messagesPerPlayer; m++) {
                    String msg = sampleMessages.get(m % sampleMessages.size());

                    long start = System.nanoTime();
                    // Pipeline: Normalize -> Aho-Corasick -> CensorTrie -> RE2J
                    String norm = TextNormalizer.normalize(msg, true);
                    ac.search(norm);
                    if (msg.contains("*")) {
                        for (String token : norm.split("\\s+")) {
                            if (token.contains("*")) {
                                trie.checkToken(token);
                            }
                        }
                    }
                    re2j.matches(norm);
                    playerDurationNano += (System.nanoTime() - start);
                }
                return playerDurationNano / messagesPerPlayer; // average nano per message for this player
            });
        }

        List<Future<Long>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        long maxPlayerAvgNano = 0;
        long totalPlayerAvgNano = 0;
        for (Future<Long> f : futures) {
            long avgNano = f.get();
            totalPlayerAvgNano += avgNano;
            maxPlayerAvgNano = Math.max(maxPlayerAvgNano, avgNano);
        }

        double overallAvgMs = (totalPlayerAvgNano / (double) numPlayers) / 1_000_000.0;
        double maxPlayerAvgMs = maxPlayerAvgNano / 1_000_000.0;

        System.out.printf("[BENCHMARK] Rebuild time: %d ms | Avg per message: %.3f ms | Max player avg: %.3f ms%n",
                rebuildTimeMs, overallAvgMs, maxPlayerAvgMs);

        assertTrue(overallAvgMs < 2.0, "Average message latency was " + overallAvgMs + " ms (must be < 2.0 ms)");
    }
}
