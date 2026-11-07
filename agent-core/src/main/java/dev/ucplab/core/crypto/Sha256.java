package dev.ucplab.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** SHA-256 over the UTF-8 bytes of a string. */
public final class Sha256 {

    private Sha256() {
    }

    public static byte[] digest(String text) {
        Objects.requireNonNull(text, "text");
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Lowercase hex, as used by snapshotHash (9.2). */
    public static String hex(String text) {
        return HexFormat.of().formatHex(digest(text));
    }
}
