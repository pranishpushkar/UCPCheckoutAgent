package dev.ucplab.ucp;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * One logical merchant call before it becomes HTTP attempts: the operation, the target, the idempotency key and the
 * frozen body (so every attempt carries the same key and the same bytes, 8.4, I7). {@code bypassGate} is true for
 * reconciliation reads, which skip the circuit breaker (8.3). {@code ucpVersion} is the version negotiated for the merchant
 * (26.2), sent as {@code version="..."} in {@code UCP-Agent}.
 */
public record ApiCall(
        AttemptOp op, String method, URI uri, Optional<IdempotencyKey> key, Optional<FrozenBody> body,
        boolean bypassGate, Optional<String> ucpVersion) {

    /** A call that names no UCP version in its {@code UCP-Agent}: discovery, where none is negotiated yet. */
    public ApiCall(
            AttemptOp op, String method, URI uri, Optional<IdempotencyKey> key, Optional<FrozenBody> body,
            boolean bypassGate) {
        this(op, method, uri, key, body, bypassGate, Optional.empty());
    }

    public ApiCall {
        Objects.requireNonNull(ucpVersion, "ucpVersion");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(body, "body");
        if (op != AttemptOp.GET && key.isEmpty()) {
            throw new IllegalArgumentException("a mutating call needs an Idempotency-Key (8.4): " + op);
        }
    }

    /** The {@code host:port} the circuit breaker is keyed on. */
    public String hostPort() {
        return uri.getHost() + ":" + (uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort());
    }
}
