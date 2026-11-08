package dev.ucplab.core.crypto;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 through {@code javax.crypto.Mac} (9.2). */
public final class HmacSha256 {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacSha256() {
    }

    public static byte[] compute(byte[] key, byte[] message) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(message, "message");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // Every Java platform is required to provide HmacSHA256.
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
