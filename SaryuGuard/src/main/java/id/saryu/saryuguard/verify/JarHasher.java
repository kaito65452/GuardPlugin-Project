package id.saryu.saryuguard.verify;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility for hashing jar files.
 */
public class JarHasher {

    public static @NotNull String sha256(@NotNull File file) throws IOException {
        return hashFile(file, "SHA-256");
    }

    public static @NotNull String sha1(@NotNull File file) throws IOException {
        return hashFile(file, "SHA-1");
    }

    public static @NotNull String sha512(@NotNull File file) throws IOException {
        return hashFile(file, "SHA-512");
    }

    private static @NotNull String hashFile(@NotNull File file, @NotNull String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[8192];
            try (FileInputStream in = new FileInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Digest algorithm not available: " + algorithm, e);
        }
    }
}
