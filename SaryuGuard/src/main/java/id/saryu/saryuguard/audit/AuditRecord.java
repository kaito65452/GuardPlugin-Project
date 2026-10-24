package id.saryu.saryuguard.audit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * In-memory audit event before encryption / chain hashing.
 */
public final class AuditRecord {

    public final @NotNull AuditEventType type;
    public final long createdAt;
    public final @Nullable String actorUuid;
    public final @Nullable String actorName;
    public final @Nullable String targetUuid;
    public final @Nullable String targetName;
    public final @Nullable String ipPlain;
    public final @Nullable String detailsPlain;

    public AuditRecord(
            @NotNull AuditEventType type,
            long createdAt,
            @Nullable String actorUuid,
            @Nullable String actorName,
            @Nullable String targetUuid,
            @Nullable String targetName,
            @Nullable String ipPlain,
            @Nullable String detailsPlain
    ) {
        this.type = type;
        this.createdAt = createdAt;
        this.actorUuid = actorUuid;
        this.actorName = actorName;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.ipPlain = ipPlain;
        this.detailsPlain = detailsPlain;
    }

    public static @NotNull AuditRecord of(
            @NotNull AuditEventType type,
            @Nullable String actorUuid,
            @Nullable String actorName,
            @Nullable String details
    ) {
        return new AuditRecord(type, System.currentTimeMillis(),
                actorUuid, actorName, null, null, null, details);
    }
}
