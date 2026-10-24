package id.saryu.saryuguard.audit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict redaction for audit records: auth arguments are never stored;
 * optional IP masking for CGNAT/privacy.
 */
public final class AuditRedaction {

    private static final Set<String> AUTH_COMMANDS = Set.of(
            "login", "l", "register", "reg", "authme", "unregister", "changepassword",
            "changepass", "email", "2fa", "totp", "code"
    );

    private static final Set<String> SENSITIVE_COMMANDS = Set.of(
            "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "stop",
            "reload", "restart", "whitelist", "gamemode", "gm", "give", "tp", "teleport",
            "lp", "luckperms", "pex", "permissions", "sudo", "execute", "timings",
            "plugins", "pl", "version", "ver", "bukkit:op", "bukkit:deop", "minecraft:op",
            "minecraft:deop", "sg", "saryuguard", "acm"
    );

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private AuditRedaction() {}

    /**
     * Normalize a raw chat/command string: trim, collapse spaces, strip leading '/', lowercase label.
     */
    public static @NotNull String normalizeCommand(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("/")) s = s.substring(1);
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        return s;
    }

    public static @NotNull String commandLabel(@NotNull String normalized) {
        int sp = normalized.indexOf(' ');
        String label = sp < 0 ? normalized : normalized.substring(0, sp);
        // Strip namespace if present for auth/sensitive checks
        int colon = label.lastIndexOf(':');
        if (colon >= 0 && colon < label.length() - 1) {
            label = label.substring(colon + 1);
        }
        return label.toLowerCase(Locale.ROOT);
    }

    public static boolean isAuthCommand(@Nullable String raw) {
        String norm = normalizeCommand(raw);
        if (norm.isEmpty()) return false;
        return AUTH_COMMANDS.contains(commandLabel(norm));
    }

    public static boolean isSensitiveCommand(@Nullable String raw) {
        String norm = normalizeCommand(raw);
        if (norm.isEmpty()) return false;
        String label = commandLabel(norm);
        if (AUTH_COMMANDS.contains(label)) return false; // auth handled separately – never log args
        String full = norm.toLowerCase(Locale.ROOT);
        int sp = full.indexOf(' ');
        String head = sp < 0 ? full : full.substring(0, sp);
        return SENSITIVE_COMMANDS.contains(label) || SENSITIVE_COMMANDS.contains(head);
    }

    /**
     * Produce a safe command string for storage.
     * Auth commands → label only ({@code /login <redacted>}).
     * Other commands → full normalized text (bounded).
     */
    public static @NotNull String redactCommand(@Nullable String raw) {
        String norm = normalizeCommand(raw);
        if (norm.isEmpty()) return "";
        if (isAuthCommand(norm)) {
            return "/" + commandLabel(norm) + " <redacted>";
        }
        String out = "/" + norm;
        if (out.length() > 256) {
            out = out.substring(0, 256);
        }
        return out;
    }

    /**
     * Mask IPv4/IPv6 for audit storage when {@code database.encryption.mask-ip} is true.
     * Example: {@code 192.168.1.42} → {@code 192.168.1.xxx}
     */
    public static @Nullable String maskIp(@Nullable String ip, boolean enabled) {
        if (ip == null || ip.isBlank()) return ip;
        if (!enabled) return ip;
        String trimmed = ip.trim();
        if (trimmed.contains(".")) {
            // IPv4
            int lastDot = trimmed.lastIndexOf('.');
            if (lastDot > 0) {
                return trimmed.substring(0, lastDot + 1) + "xxx";
            }
        }
        if (trimmed.contains(":")) {
            // IPv6 – keep first 4 hextets
            String[] parts = trimmed.split(":", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(i < 4 ? parts[i] : "xxxx");
            }
            return sb.toString();
        }
        return "xxx";
    }
}
