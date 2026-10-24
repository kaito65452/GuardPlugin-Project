package id.saryu.saryuguard.audit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Tamper-evident hash chain: {@code hash = SHA-256(prev_hash || canonical_bytes)}.
 *
 * <p>Canonical encoding is a stable pipe-delimited UTF-8 string of event fields
 * (never includes ciphertext – hash covers plaintext forensic fields before encryption).</p>
 */
public final class AuditChain {

    /** Genesis previous-hash (64 zero hex chars). */
    public static final String GENESIS_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private AuditChain() {}

    /**
     * Build canonical bytes for hashing. Null fields become empty strings.
     * Order is fixed and must never change without a schema bump.
     */
    public static @NotNull byte[] canonicalBytes(
            @NotNull String eventType,
            long createdAt,
            @Nullable String actorUuid,
            @Nullable String actorName,
            @Nullable String targetUuid,
            @Nullable String targetName,
            @Nullable String detailsPlain,
            @Nullable String ipPlain
    ) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(escapeField(eventType)).append('|')
                .append(createdAt).append('|')
                .append(escapeField(actorUuid)).append('|')
                .append(escapeField(actorName)).append('|')
                .append(escapeField(targetUuid)).append('|')
                .append(escapeField(targetName)).append('|')
                .append(escapeField(detailsPlain)).append('|')
                .append(escapeField(ipPlain));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Compute next chain hash: SHA-256(hex-decode(prev) || canonical).
     */
    public static @NotNull String nextHash(@NotNull String prevHashHex, @NotNull byte[] canonical) {
        Objects.requireNonNull(prevHashHex, "prevHashHex");
        Objects.requireNonNull(canonical, "canonical");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] prev = decodeHash(prevHashHex);
            md.update(prev);
            md.update(canonical);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static @NotNull byte[] decodeHash(@NotNull String hex) {
        String h = hex.trim().toLowerCase();
        if (h.length() != 64 || !h.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            // Treat malformed as genesis to fail verification loudly later
            return HexFormat.of().parseHex(GENESIS_HASH);
        }
        return HexFormat.of().parseHex(h);
    }

    public static boolean isValidHashHex(@Nullable String hex) {
        if (hex == null) return false;
        String h = hex.trim().toLowerCase();
        if (h.length() != 64) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
        }
        return true;
    }

    private static String escapeField(@Nullable String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("|", "\\|");
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
