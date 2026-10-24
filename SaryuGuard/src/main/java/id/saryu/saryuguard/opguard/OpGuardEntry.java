package id.saryu.saryuguard.opguard;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * In-memory representation of an approved OP entry.
 * Wraps the DB record with parsed UUID for fast lookups.
 */
public record OpGuardEntry(
        @NotNull UUID uuid,
        @Nullable String lastKnownName,
        @NotNull String approver,
        long approvedAt,
        @Nullable String notes
) {

    /** Create from DAO record. */
    public static @NotNull OpGuardEntry fromDao(@NotNull OpGuardDao.OpEntry row) {
        return new OpGuardEntry(
                UUID.fromString(row.uuid()),
                row.playerName(),
                row.approver(),
                row.approvedAt(),
                row.notes()
        );
    }

    /** Convert to DAO record. */
    public @NotNull OpGuardDao.OpEntry toDao() {
        return new OpGuardDao.OpEntry(
                uuid.toString(),
                lastKnownName,
                approver,
                approvedAt,
                notes
        );
    }
}
