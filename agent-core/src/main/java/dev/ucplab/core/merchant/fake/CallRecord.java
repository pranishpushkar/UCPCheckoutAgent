package dev.ucplab.core.merchant.fake;

import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.SessionId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One request as the merchant side saw it, like the proxy log in section 12. A call from the client and each delivery
 * to the merchant (a duplicate, or a held request arriving later) are separate records.
 *
 * @param delivered true when this record is the request reaching the merchant's handler
 * @param fault the delivery fault applied to the client call, or {@code none}
 */
public record CallRecord(
        int seq, MerchantCall call, Optional<SessionId> sessionId, Optional<IdempotencyKey> key, String bodySha256,
        boolean delivered, String fault, Instant at) {

    public CallRecord {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(bodySha256, "bodySha256");
        Objects.requireNonNull(fault, "fault");
        Objects.requireNonNull(at, "at");
    }
}
