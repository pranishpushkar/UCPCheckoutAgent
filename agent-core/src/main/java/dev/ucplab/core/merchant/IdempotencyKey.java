package dev.ucplab.core.merchant;

import dev.ucplab.core.crypto.Base32;
import dev.ucplab.core.crypto.Sha256;
import dev.ucplab.core.intent.IntentId;
import java.util.Objects;

/**
 * The {@code Idempotency-Key} of a mutating merchant call. Contract 7.4, decision D3, invariant I7.
 *
 * <p>{@code key = 'ucp-' + first 26 chars of base32(sha256(intentId + '|' + op + '|' + revision))}
 */
public record IdempotencyKey(String value) {

    private static final String PREFIX = "ucp-";
    private static final int HASH_CHARS = 26;

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("IdempotencyKey must not be blank");
        }
    }

    /**
     * Derives the key. {@code revision} is 0 for create and cancel; for update, the number of
     * updates already issued for the intent; for complete, the intent's requote_count at the
     * time of approval (7.4).
     */
    public static IdempotencyKey derive(IntentId intentId, MutatingOp op, int revision) {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(op, "op");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative: " + revision);
        }
        if ((op == MutatingOp.CREATE || op == MutatingOp.CANCEL) && revision != 0) {
            throw new IllegalArgumentException("revision must be 0 for " + op.wireName() + ": " + revision);
        }
        String input = intentId.value() + '|' + op.wireName() + '|' + revision;
        return new IdempotencyKey(PREFIX + Base32.encode(Sha256.digest(input)).substring(0, HASH_CHARS));
    }
}
