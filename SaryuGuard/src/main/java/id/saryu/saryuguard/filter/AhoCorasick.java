package id.saryu.saryuguard.filter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Aho-Corasick string matching automaton.
 *
 * <p>Requirements:
 * Lapis 1: Aho-Corasick atas teks ternormalisasi, dengan batas kata dan whitelist
 * (cegah salah deteksi, termasuk kata Indonesia yang sah).</p>
 */
public class AhoCorasick {

    public static class Match {
        public final int start;
        public final int end;
        public final String keyword;

        public Match(int start, int end, String keyword) {
            this.start = start;
            this.end = end;
            this.keyword = keyword;
        }
    }

    private static class Node {
        final Map<Character, Node> children = new HashMap<>();
        Node fail;
        final List<String> outputs = new ArrayList<>();
    }

    private final Node root;
    private final Set<String> whitelist;

    public AhoCorasick(@NotNull Collection<String> keywords, @Nullable Collection<String> whitelist) {
        this.root = new Node();
        this.whitelist = whitelist != null ? new HashSet<>(whitelist) : Collections.emptySet();

        // 1. Build Trie
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            String clean = kw.trim().toLowerCase(Locale.ROOT);
            Node current = root;
            for (int i = 0; i < clean.length(); i++) {
                char c = clean.charAt(i);
                current = current.children.computeIfAbsent(c, k -> new Node());
            }
            current.outputs.add(clean);
        }

        // 2. Build Failure Links (BFS)
        Queue<Node> queue = new ArrayDeque<>();
        for (Node child : root.children.values()) {
            child.fail = root;
            queue.add(child);
        }

        while (!queue.isEmpty()) {
            Node current = queue.poll();
            for (Map.Entry<Character, Node> entry : current.children.entrySet()) {
                char c = entry.getKey();
                Node child = entry.getValue();

                Node f = current.fail;
                while (f != null && !f.children.containsKey(c)) {
                    f = f.fail;
                }
                child.fail = (f != null) ? f.children.get(c) : root;
                child.outputs.addAll(child.fail.outputs);
                queue.add(child);
            }
        }
    }

    /**
     * Search text for matches with word boundary and whitelist validation.
     *
     * @param text Normalized text
     * @return List of verified matches
     */
    public @NotNull List<Match> search(@NotNull String text) {
        List<Match> matches = new ArrayList<>();
        Node current = root;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            while (current != null && !current.children.containsKey(c)) {
                current = current.fail;
            }
            if (current == null) {
                current = root;
                continue;
            }
            current = current.children.get(c);

            if (!current.outputs.isEmpty()) {
                for (String kw : current.outputs) {
                    int start = i - kw.length() + 1;
                    int end = i + 1;

                    // 1. Check word boundary
                    if (isWordBoundary(text, start, end)) {
                        // 2. Check whitelist around the matched word
                        if (!isWhitelisted(text, start, end)) {
                            matches.add(new Match(start, end, kw));
                        }
                    }
                }
            }
        }
        return matches;
    }

    private boolean isWordBoundary(@NotNull String text, int start, int end) {
        // Boundary before start
        if (start > 0) {
            char prev = text.charAt(start - 1);
            if (Character.isLetterOrDigit(prev)) {
                return false;
            }
        }
        // Boundary after end
        if (end < text.length()) {
            char next = text.charAt(end);
            if (Character.isLetterOrDigit(next)) {
                return false;
            }
        }
        return true;
    }

    private boolean isWhitelisted(@NotNull String text, int matchStart, int matchEnd) {
        if (whitelist.isEmpty()) return false;

        // Find surrounding full word token in text
        int tokenStart = matchStart;
        while (tokenStart > 0 && Character.isLetterOrDigit(text.charAt(tokenStart - 1))) {
            tokenStart--;
        }

        int tokenEnd = matchEnd;
        while (tokenEnd < text.length() && Character.isLetterOrDigit(text.charAt(tokenEnd))) {
            tokenEnd++;
        }

        String fullToken = text.substring(tokenStart, tokenEnd).toLowerCase(Locale.ROOT);
        return whitelist.contains(fullToken);
    }
}
