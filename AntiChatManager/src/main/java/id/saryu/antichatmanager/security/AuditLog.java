package id.saryu.antichatmanager.security;

import id.saryu.antichatmanager.AntiChatManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.logging.Level;

/** Append-only, line-safe audit log under the plugin data folder. */
public final class AuditLog {

    private final AntiChatManager plugin;
    private final Path file;

    public AuditLog(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("logs").resolve("audit.log");
    }

    public void write(@NotNull String event, @NotNull String detail) {
        String line = Instant.now() + " " + clean(event) + " " + clean(detail) + System.lineSeparator();
        plugin.getLib().getSchedulerModule().runAsync(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (Files.size(file) > 5L * 1024L * 1024L) {
                    Files.writeString(file, "", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.FINE, "Audit log write failed", e);
            }
        });
    }

    private String clean(String value) {
        if (value == null) return "-";
        return value.replace('\r', ' ').replace('\n', ' ').replaceAll("[\\p{Cc}]", "?");
    }
}
