package id.saryu.saryuguard.verify;

import org.jetbrains.annotations.NotNull;

/**
 * Verification status for server plugins and jars.
 *
 * <p>Requirements:
 * Status: VERIFIED / UNVERIFIED / MISMATCH / SCAN_ERROR.
 * SCAN_ERROR tidak pernah dianggap bersih.
 * Hangar dan Spigot: cocok nama/author saja = UNVERIFIED berconfidence rendah,
 * TIDAK PERNAH VERIFIED, sampai endpoint dokumen API diverifikasi.</p>
 */
public enum VerifyStatus {
    VERIFIED("VERIFIED", "<green><bold>VERIFIED</bold></green>"),
    UNVERIFIED("UNVERIFIED", "<yellow><bold>UNVERIFIED</bold></yellow>"),
    MISMATCH("MISMATCH", "<red><bold>MISMATCH</bold></red>"),
    SCAN_ERROR("SCAN_ERROR", "<dark_red><bold>SCAN_ERROR</bold></dark_red>");

    private final String label;
    private final String formatted;

    VerifyStatus(String label, String formatted) {
        this.label = label;
        this.formatted = formatted;
    }

    public @NotNull String label() {
        return label;
    }

    public @NotNull String formatted() {
        return formatted;
    }

    public boolean isClean() {
        // SCAN_ERROR is never clean
        return this == VERIFIED;
    }
}
