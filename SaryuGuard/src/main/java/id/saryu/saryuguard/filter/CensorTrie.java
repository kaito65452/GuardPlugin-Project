package id.saryu.saryuguard.filter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Trie search for censored tokens containing asterisk wildcards ('*') without regular expressions.
 *
 * <p>Requirements:
 * Karakter sensor (*): token yang mengandung karakter sensor dicek dengan penelusuran trie
 * pada kamus (tanpa regex). Maksimal 2 karakter sensor per token, panjang minimal, dan
 * whitelist agar tidak over-match.</p>
 */
public class CensorTrie {

    private static class Node {
        final Map<Character, Node> children = new HashMap<>();
        boolean isEndOfWord = false;
        String word = null;
    }

    private final Node root = new Node();
    private final Set<String> whitelist;
    private final int minLength;
    private final int maxAsterisks;

    public CensorTrie(@NotNull Collection<String> dictionary, @Nullable Collection<String> whitelist, int minLength, int maxAsterisks) {
        this.whitelist = whitelist != null ? new HashSet<>(whitelist) : Collections.emptySet();
        this.minLength = Math.max(3, minLength);
        this.maxAsterisks = Math.max(1, maxAsterisks);

        for (String w : dictionary) {
            if (w == null || w.isBlank()) continue;
            String clean = w.trim().toLowerCase(Locale.ROOT);
            Node curr = root;
            for (int i = 0; i < clean.length(); i++) {
                char c = clean.charAt(i);
                curr = curr.children.computeIfAbsent(c, k -> new Node());
            }
            curr.isEndOfWord = true;
            curr.word = clean;
        }
    }

    /**
     * Check if a token containing '*' matches any word in the dictionary trie.
     *
     * @param token Normalized single word token (e.g. "b*ngo", "an**ng")
     * @return Matched dictionary word or null if no match
     */
    public @Nullable String checkToken(@NotNull String token) {
        if (token.length() < minLength) {
            return null;
        }

        int asteriskCount = 0;
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) == '*') {
                asteriskCount++;
            }
        }

        // Must contain at least 1 and at most maxAsterisks (default 2)
        if (asteriskCount == 0 || asteriskCount > maxAsterisks) {
            return null;
        }

        if (whitelist.contains(token)) {
            return null;
        }

        return dfs(root, token, 0);
    }

    private @Nullable String dfs(Node node, String token, int index) {
        if (index == token.length()) {
            return node.isEndOfWord ? node.word : null;
        }

        char c = token.charAt(index);
        if (c != '*') {
            Node child = node.children.get(c);
            if (child == null) return null;
            return dfs(child, token, index + 1);
        } else {
            // Wildcard branch: explore all child letters
            for (Map.Entry<Character, Node> entry : node.children.entrySet()) {
                char letter = entry.getKey();
                if (Character.isLetter(letter)) {
                    String match = dfs(entry.getValue(), token, index + 1);
                    if (match != null) {
                        return match;
                    }
                }
            }
            return null;
        }
    }
}
