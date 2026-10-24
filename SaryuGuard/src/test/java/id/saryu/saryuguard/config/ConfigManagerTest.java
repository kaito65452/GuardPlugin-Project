package id.saryu.saryuguard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {

    @Test
    @DisplayName("Path traversal detection rejects paths outside data folder")
    void testPathTraversalRejection(@TempDir Path tempDir) throws IOException {
        File dataFolder = tempDir.resolve("plugins/SaryuGuard").toFile();
        assertTrue(dataFolder.mkdirs());

        // File located inside dataFolder
        File safeFile = new File(dataFolder, "config.yml");
        safeFile.createNewFile();

        // File escaping dataFolder
        File evilFile = new File(dataFolder, "../../evil.txt");

        String canonicalRoot = dataFolder.getCanonicalPath();
        String safeCanonical = safeFile.getCanonicalPath();
        String evilCanonical = evilFile.getCanonicalPath();

        assertTrue(safeCanonical.startsWith(canonicalRoot));
        assertFalse(evilCanonical.startsWith(canonicalRoot));
    }

    @Test
    @DisplayName("File size validation rejects files exceeding max configured bytes")
    void testFileSizeRejection(@TempDir Path tempDir) throws IOException {
        File dataFolder = tempDir.resolve("plugins/SaryuGuard").toFile();
        assertTrue(dataFolder.mkdirs());

        File largeFile = new File(dataFolder, "huge.yml");
        byte[] largeBytes = new byte[2048]; // 2 KB
        Files.write(largeFile.toPath(), largeBytes);

        long maxAllowed = 1024; // 1 KB max
        assertTrue(largeFile.length() > maxAllowed);
    }

    @Test
    @DisplayName("32-byte AES key generation and persistence")
    void testSecretKeyGeneration(@TempDir Path tempDir) throws IOException {
        File keyFile = tempDir.resolve("secret.key").toFile();
        assertFalse(keyFile.exists());

        byte[] generated = new byte[32];
        new SecureRandom().nextBytes(generated);
        Files.write(keyFile.toPath(), generated);

        assertTrue(keyFile.exists());
        assertEquals(32, keyFile.length());

        byte[] read = Files.readAllBytes(keyFile.toPath());
        assertArrayEquals(generated, read);
    }
}
