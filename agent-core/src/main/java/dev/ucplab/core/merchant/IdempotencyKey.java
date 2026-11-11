package dev.ucplab.core.merchant;

import dev.ucplab.core.crypto.Sha256;
import dev.ucplab.core.intent.IntentId;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code Idempotency-Key} of a mutating merchant call. Contract 7.4, decision D3, invariant I7.
 *
 * <p>{@code key = uuid8(sha256(intentId + '|' + op + '|' + revision))}: the first 16 bytes of the digest with the
 * version nibble set to 8 and the variant bits to {@code 10} (RFC 9562), as a canonical lower-case UUID. The 2026-08-25
 * OpenAPI declares the header as {@code format: uuid}.
 */
public record IdempotencyKey(String value) {

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
        byte[] b = Arrays.copyOf(Sha256.digest(input), 16);
        b[6] = (byte) ((b[6] & 0x0F) | 0x80);
        b[8] = (byte) ((b[8] & 0x3F) | 0x80);
        ByteBuffer buf = ByteBuffer.wrap(b);
        return new IdempotencyKey(new UUID(buf.getLong(), buf.getLong()).toString());
    }
}
