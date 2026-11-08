package dev.ucplab.core.intent;

import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One merchant call, written before it is made (7.3 step 3). Mirrors {@code merchant_attempt} (section 10).
 * {@code requestBody} is the frozen body kept for byte-identical replay (I7); it can hold a payment
 * credential, so {@link #toString()} leaves it out.
 */
public record MerchantAttempt(
        AttemptId id,
        IntentId intentId,
        AttemptOp op,
        int revision,
        Optional<IdempotencyKey> idempotencyKey,
        Optional<String> requestHash,
        Optional<FrozenBody> requestBody,
        AttemptStatus status,
        Optional<Integer> httpStatus,
        Optional<String> responseJson,
        Instant sentAt,
        Optional<Instant> respondedAt) {

    public MerchantAttempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(requestBody, "requestBody");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(responseJson, "responseJson");
        Objects.requireNonNull(sentAt, "sentAt");
        Objects.requireNonNull(respondedAt, "respondedAt");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative: " + revision);
        }
    }

    /** A new attempt in status SENT, as written in 7.3 step 3. */
    public static MerchantAttempt sent(
            AttemptId id, IntentId intentId, AttemptOp op, int revision, Optional<IdempotencyKey> key,
            Optional<FrozenBody> body, Instant now) {
        return new MerchantAttempt(
                id, intentId, op, revision, key, Optional.empty(), body, AttemptStatus.SENT,
                Optional.empty(), Optional.empty(), now, Optional.empty());
    }

    MerchantAttempt resolved(
            AttemptStatus newStatus, Optional<Integer> newHttpStatus, Optional<String> newResponseJson,
            Instant at) {
        return new MerchantAttempt(
                id, intentId, op, revision, idempotencyKey, requestHash, requestBody, newStatus, newHttpStatus,
                newResponseJson, sentAt, Optional.of(at));
    }

    @Override
    public String toString() {
        return "MerchantAttempt[id=" + id + ", intentId=" + intentId + ", op=" + op + ", revision=" + revision
                + ", status=" + status + ']';
    }
}
