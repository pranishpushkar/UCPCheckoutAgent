package dev.ucplab.faultproxy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One request as the proxy saw it (§12 Admin API). An entry exists from the moment the request arrives and is replaced
 * by an updated copy as the request is forwarded and answered. It never holds a request or response body, and no header
 * other than {@code Request-Id} and {@code Idempotency-Key} (I8); {@code bodyHmac} is the keyed hash of the body.
 *
 * <p>{@code forwards} counts the copies the proxy sent to the merchant (0, 1, or 2 for {@code duplicate_request}), and
 * {@code forwarded} is {@code forwards > 0}. A copy counts once it is handed to the HTTP client, so a copy that could
 * not reach the merchant is counted and also flagged {@code upstreamError}.
 *
 * <p>{@code rewrite} is set only for {@code forward_then_rewrite_response}: {@code replaced}, {@code no_match} (a JSON
 * body without the literal) or {@code not_json}; it says whether a replacement happened, never the strings (§12).
 *
 * <p>{@code settled} turns true exactly once, after the last thing the proxy does for the request (§12): until then
 * the request may still reach the merchant or the client.
 */
public record LogEntry(long seq, Instant at, String method, String path, Optional<String> requestId,
                       Optional<String> idempotencyKey, Optional<String> bodyHmac, int forwards,
                       OptionalInt merchantStatus, Optional<String> action, Optional<String> ruleId,
                       boolean upstreamError, boolean clientGone, boolean settled, Optional<String> rewrite) {

    public LogEntry {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(bodyHmac, "bodyHmac");
        Objects.requireNonNull(merchantStatus, "merchantStatus");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(rewrite, "rewrite");
        if (forwards < 0 || forwards > 2) {
            throw new IllegalArgumentException("forwards must be 0, 1 or 2: " + forwards);
        }
    }

    /** An entry with no rewrite outcome. */
    public LogEntry(long seq, Instant at, String method, String path, Optional<String> requestId,
                    Optional<String> idempotencyKey, Optional<String> bodyHmac, int forwards,
                    OptionalInt merchantStatus, Optional<String> action, Optional<String> ruleId,
                    boolean upstreamError, boolean clientGone, boolean settled) {
        this(seq, at, method, path, requestId, idempotencyKey, bodyHmac, forwards, merchantStatus, action, ruleId,
                upstreamError, clientGone, settled, Optional.empty());
    }

    public boolean forwarded() {
        return forwards > 0;
    }

    LogEntry withForward() {
        return new LogEntry(seq, at, method, path, requestId, idempotencyKey, bodyHmac, forwards + 1, merchantStatus,
                action, ruleId, upstreamError, clientGone, settled, rewrite);
    }

    LogEntry withMerchantStatus(int status) {
        return new LogEntry(seq, at, method, path, requestId, idempotencyKey, bodyHmac, forwards,
                OptionalInt.of(status), action, ruleId, upstreamError, clientGone, settled, rewrite);
    }

    LogEntry withUpstreamError() {
        return new LogEntry(seq, at, method, path, requestId, idempotencyKey, bodyHmac, forwards, merchantStatus,
                action, ruleId, true, clientGone, settled, rewrite);
    }

    LogEntry withClientGone() {
        return new LogEntry(seq, at, method, path, requestId, idempotencyKey, bodyHmac, forwards, merchantStatus,
                action, ruleId, upstreamError, true, settled, rewrite);
    }

    LogEntry withRewrite(String outcome) {
        return new LogEntry(seq, at, method, path, requestId, idempotencyKey, bodyHmac, forwards, merchantStatus,
                action, ruleId, upstreamError, clientGone, settled, Optional.of(outcome));
    }

    LogEntry withSettled() {
        if (settled) {
            throw new IllegalStateException("entry " + seq + " is already settled");
        }
        return new LogEntry(seq, at, method, path, requestId, idempotencyKey, bodyHmac, forwards, merchantStatus,
                action, ruleId, upstreamError, clientGone, true, rewrite);
    }

    /** The JSON form served by {@code GET /_proxy/log}; absent values are JSON {@code null}. */
    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("at", at.toString());
        m.put("method", method);
        m.put("path", path);
        m.put("requestId", orNull(requestId));
        m.put("idempotencyKey", orNull(idempotencyKey));
        m.put("bodyHmac", orNull(bodyHmac));
        m.put("forwarded", forwarded());
        m.put("forwards", forwards);
        m.put("merchantStatus", merchantStatus.isPresent() ? (Object) merchantStatus.getAsInt() : Json.NULL);
        m.put("action", orNull(action));
        m.put("ruleId", orNull(ruleId));
        m.put("upstreamError", upstreamError);
        m.put("clientGone", clientGone);
        m.put("settled", settled);
        m.put("rewrite", orNull(rewrite));
        return m;
    }

    private static Object orNull(Optional<String> value) {
        return value.<Object>map(v -> v).orElse(Json.NULL);
    }
}
