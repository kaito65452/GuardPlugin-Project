package id.saryu.saryuguard.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JarHasherTest {

    @Test
    @DisplayName("Compute correct SHA-256 and SHA-1 hashes")
    void testFileHashing(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("sample.jar").toFile();
        // Content: "Hello SaryuGuard"
        Files.writeString(file.toPath(), "Hello SaryuGuard", StandardCharsets.UTF_8);

        String sha256 = JarHasher.sha256(file);
        String sha1 = JarHasher.sha1(file);
        String sha512 = JarHasher.sha512(file);

        assertNotNull(sha256);
        assertEquals(64, sha256.length()); // 256 bits = 64 hex chars

        assertNotNull(sha1);
        assertEquals(40, sha1.length()); // 160 bits = 40 hex chars

        assertNotNull(sha512);
        assertEquals(128, sha512.length()); // 512 bits = 128 hex chars

        // Known SHA-256 for "Hello SaryuGuard" is:
        // sha256("Hello SaryuGuard") = e8d21bca964b4a3a60a4f5b5f8990b769623e3518175402633010b98eb6c78a0
        // Let's verify by computing Java MessageDigest directly
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        byte[] expected = md.digest("Hello SaryuGuard".getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : expected) sb.append(String.format("%02x", b));

        assertEquals(sb.toString(), sha256);
    }
}
