package id.saryu.saryuguard.audit;

import org.jetbrains.annotations.NotNull;

/**
 * Categories of forensic audit events.
 */
public enum AuditEventType {
    PLAYER_JOIN("player.join"),
    PLAYER_QUIT("player.quit"),
    SENSITIVE_COMMAND("command.sensitive"),
    OP_CHANGE("op.change"),
    OP_VIOLATION("op.violation"),
    JAR_CHANGE("jar.change"),
    JAR_APPROVE("jar.approve"),
    SCAN_RESULT("scan.result"),
    MFA_SETUP("mfa.setup"),
    MFA_AUTH("mfa.auth"),
    MFA_LOCK("mfa.lock"),
    MFA_RESET("mfa.reset"),
    CRYPT_ROTATE("crypt.rotate"),
    MODULE("module.event"),
    SYSTEM("system");

    private final String code;

    AuditEventType(@NotNull String code) {
        this.code = code;
    }

    public @NotNull String code() {
        return code;
    }

    public static @NotNull AuditEventType fromCode(@NotNull String code) {
        for (AuditEventType t : values()) {
            if (t.code.equalsIgnoreCase(code) || t.name().equalsIgnoreCase(code)) {
                return t;
            }
        }
        return SYSTEM;
    }
}
