package dev.ucplab.core.approval;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * The HMAC key for approval tokens ({@code APPROVAL_SECRET}). Never printed (I8). Built once at
 * startup, so an unacceptable secret fails startup (9.2).
 */
public final class ApprovalSecret {

    /** Shortest secret accepted, in bytes (9.2). */
    public static final int MIN_BYTES = 32;

    /** Prefix of the placeholder secrets in .env.example; refused in the prod profile (9.2). */
    public static final String PLACEHOLDER_PREFIX = "change-me";

    private final byte[] key;

    private ApprovalSecret(byte[] key) {
        if (key.length < MIN_BYTES) {
            throw new IllegalArgumentException("approval secret must be at least " + MIN_BYTES + " bytes");
        }
        this.key = key.clone();
    }

    public static ApprovalSecret of(byte[] key) {
        Objects.requireNonNull(key, "key");
        return new ApprovalSecret(key);
    }

    /** The UTF-8 bytes of a configured secret string. */
    public static ApprovalSecret of(String secret) {
        Objects.requireNonNull(secret, "secret");
        return new ApprovalSecret(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * As {@link #of(String)}, and also refuses a placeholder starting with "change-me" (prod profile). agent-service's
     * {@code ServiceProperties} uses it when {@code AGENT_PROFILE} is prod, at startup (9.2).
     */
    public static ApprovalSecret ofProduction(String secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.startsWith(PLACEHOLDER_PREFIX)) {
            throw new IllegalArgumentException("approval secret is a placeholder; set a real secret for prod");
        }
        return of(secret);
    }

    byte[] key() {
        return key.clone();
    }

    @Override
    public String toString() {
        return "ApprovalSecret[redacted]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ApprovalSecret other && Arrays.equals(key, other.key);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(key);
    }
}
