package dev.ucplab.ucp;

import dev.ucplab.core.merchant.FrozenBody;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One HTTP attempt handed to an {@link HttpTransport}. The body is the frozen canonical JSON, sent byte for byte
 * (8.4, I7). {@link #toString()} shows only the method and URI: headers can carry credentials and the body a payment
 * token (I8).
 */
public record HttpCall(
        String method, URI uri, Map<String, String> headers, Optional<FrozenBody> body, Duration timeout) {

    public HttpCall {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
    }

    @Override
    public String toString() {
        return "HttpCall[" + method + " " + uri + "]";
    }
}
