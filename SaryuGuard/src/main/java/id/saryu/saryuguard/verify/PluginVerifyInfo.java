package id.saryu.saryuguard.verify;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Verification record for a plugin jar file.
 */
public class PluginVerifyInfo {

    public final String pluginName;
    public final File jarFile;
    public final String sha256;
    public final long jarSize;
    public final long lastModified;

    public VerifyStatus status = VerifyStatus.UNVERIFIED;
    public int confidence = 0;
    public String source = "LOCAL";
    public String details = "";

    @Nullable public String approvedBy;
    public long approvedAt = 0;

    @Nullable public String projectSlug;
    @Nullable public String projectVersion;
    @Nullable public String errorMessage;

    public PluginVerifyInfo(
            @NotNull String pluginName,
            @NotNull File jarFile,
            @NotNull String sha256,
            long jarSize,
            long lastModified
    ) {
        this.pluginName = pluginName;
        this.jarFile = jarFile;
        this.sha256 = sha256;
        this.jarSize = jarSize;
        this.lastModified = lastModified;
    }
}
