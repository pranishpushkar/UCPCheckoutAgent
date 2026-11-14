package dev.ucplab.ucp;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import javax.net.ssl.SSLException;

/**
 * Turns what a transport observed into a {@link CallOutcome} (8.1). It decides nothing about retries; that is
 * {@code RetryExecutor} (8.2).
 *
 * <p><b>NotSent is a whitelist.</b> It is returned only for: {@link HttpConnectTimeoutException}; a
 * {@link ConnectException} whose cause chain contains an {@link UnresolvedAddressException} (the name did not resolve, so
 * no connection existed); and, from {@link #circuitOpen}, a call the breaker rejected. Everything else is
 * {@code Uncertain}, including every other {@code ConnectException} and every {@link SSLException}, handshake failures
 * included. Why: the JDK raises the same types for different things on different platforms. On Linux a connection
 * reset during connect is reported as {@code ConnectException: Connection reset by peer (connect failed)}, and a peer
 * that does not speak TLS gives a plain {@code SSLException: Unrecognized SSL message}, not an
 * {@code SSLHandshakeException}. The earlier decision A (a {@code ConnectException} is always {@code NotSent}) rested on
 * a raw-socket test that held on macOS only, so the conservative rule applies: when in doubt, Uncertain. A timeout,
 * a reset, an EOF and any unexpected exception are {@code Uncertain} as well. The rule is the same for {@code get},
 * which is retried either way (8.2).
 *
 * <p><b>Replies.</b> Status handling follows 8.1 and decisions B, C and D: a mutating call (create, update, complete,
 * cancel) that gets an answer it cannot trust is {@code Uncertain}, because the merchant may have acted; {@code get}
 * is a read, so the same answer is a {@code ProtocolError}. Nothing here follows a redirect (the transport does not).
 * Causes and error content are cleaned of control characters and never repeat a body.
 */
public final class FailureClassifier {

    private static final Duration MAX_RETRY_AFTER = Duration.ofDays(1);
    private static final int MAX_CAUSE = 200;

    private final Clock clock;

    /** @param clock the injected clock, used to turn a {@code Retry-After} HTTP date into a duration */
    public FailureClassifier(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A call the circuit breaker (8.3) rejected: it never left, so NotSent. */
    public static CallOutcome<CheckoutView> circuitOpen(String hostPort) {
        Objects.requireNonNull(hostPort, "hostPort");
        return new CallOutcome.NotSent<>("circuit breaker open for " + clean(hostPort, 80));
    }

    /** A call a gate refused, by the gate's {@link Gate#reason()}: it never left, so NotSent. */
    public static CallOutcome<CheckoutView> refused(String reason, String hostPort) {
        Objects.requireNonNull(reason, "reason");
        return "rate_limited".equals(reason)
                ? new CallOutcome.NotSent<>("rate limited for " + clean(Objects.requireNonNull(hostPort, "hostPort"), 80))
                : circuitOpen(hostPort);
    }

    /** Classifies a failure to get a complete reply. {@code NotSent} only for the whitelist above. */
    public CallOutcome<CheckoutView> classify(AttemptOp op, Throwable failure) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(failure, "failure");
        String cause = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + clean(failure.getMessage(), MAX_CAUSE));
        // HttpConnectTimeoutException is an HttpTimeoutException, which is Uncertain: it is tested first, by itself.
        boolean neverWritten = failure instanceof HttpConnectTimeoutException || isUnresolvedAddress(failure);
        return neverWritten ? new CallOutcome.NotSent<>(cause) : new CallOutcome.Uncertain<>(cause);
    }

    /** A ConnectException with an UnresolvedAddressException anywhere in its cause chain: DNS failed, no socket existed. */
    private static boolean isUnresolvedAddress(Throwable failure) {
        if (!(failure instanceof ConnectException)) {
            return false;
        }
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 16; t = t.getCause(), depth++) {
            if (t instanceof UnresolvedAddressException) {
                return true;
            }
        }
        return false;
    }

    /** Classifies a reply to {@code op} by its status and body (8.1, 8.2, decisions B, C, D). */
    public CallOutcome<CheckoutView> classify(AttemptOp op, HttpReply reply) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(reply, "reply");
        int status = reply.status();
        if (status == 200 || status == 201) {
            return fromBody(op, reply);
        }
        return classifyError(op, reply);
    }

    /**
     * Classifies a reply whose status is not 200 or 201 (8.1, 8.2, decisions C and D). Used by the gateway for a body
     * that is not a checkout, such as the discovery profile, where the caller reads a 200 itself.
     */
    public CallOutcome<CheckoutView> classifyError(AttemptOp op, HttpReply reply) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(reply, "reply");
        int status = reply.status();
        if (status == 400 || status == 401 || status == 403 || status == 404 || status == 409 || status == 422) {
            return protocolError(reply, Optional.empty());
        }
        if (status == 429 || status == 503) {
            if (op == AttemptOp.COMPLETE) {
                // Decision D: it does not prove that complete never reached the merchant, so the intent reconciles.
                return new CallOutcome.Uncertain<>("http " + status + " on complete");
            }
            return protocolError(reply, reply.header("retry-after").flatMap(this::retryAfter));
        }
        if (status >= 500 && status <= 599 && status != 501) {
            return new CallOutcome.Uncertain<>("http " + status);
        }
        // Unlisted: 1xx, other 2xx, 3xx (never followed), other 4xx, 501.
        return op == AttemptOp.GET
                ? new CallOutcome.ProtocolError<>(status, "unexpected_status", "HTTP " + status, Optional.empty())
                : new CallOutcome.Uncertain<>("unexpected http status " + status);
    }

    private CallOutcome<CheckoutView> fromBody(AttemptOp op, HttpReply reply) {
        CheckoutParser.Parsed parsed = CheckoutParser.parseCheckout(reply.body());
        return switch (parsed) {
            case CheckoutParser.Parsed.Checkout c ->
                    new CallOutcome.Ok<>(reply.status(), c.view(), c.view().messages());
            case CheckoutParser.Parsed.CreateRejected r when op == AttemptOp.CREATE && reply.status() == 200 ->
                    new CallOutcome.Rejected<>(reply.status(), r.messages());
            case CheckoutParser.Parsed.CreateRejected r -> invalid(op, reply.status(), "unexpected create-rejected envelope");
            case CheckoutParser.Parsed.Invalid i -> invalid(op, reply.status(), i.reason());
        };
    }

    private static CallOutcome<CheckoutView> invalid(AttemptOp op, int status, String reason) {
        return op == AttemptOp.GET
                ? new CallOutcome.ProtocolError<>(status, "invalid_response", reason, Optional.empty())
                : new CallOutcome.Uncertain<>("invalid response: " + reason);
    }

    private static CallOutcome<CheckoutView> protocolError(HttpReply reply, Optional<Duration> retryAfter) {
        CheckoutParser.ErrorBody body = CheckoutParser.parseError(reply.body());
        return new CallOutcome.ProtocolError<>(reply.status(), body.code(), body.content(), retryAfter);
    }

    /** {@code Retry-After}: whole seconds or an HTTP-date. Empty if absent or unreadable; clamped to one day. */
    private Optional<Duration> retryAfter(String value) {
        String v = value.trim();
        if (!v.isEmpty() && v.chars().allMatch(c -> c >= '0' && c <= '9')) {
            try {
                return Optional.of(min(Duration.ofSeconds(Long.parseLong(v))));
            } catch (NumberFormatException tooBig) {
                return Optional.of(MAX_RETRY_AFTER);
            }
        }
        try {
            Duration wait = Duration.between(clock.instant(), ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return Optional.of(wait.isNegative() ? Duration.ZERO : min(wait));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Duration min(Duration d) {
        return d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d;
    }

    private static String clean(String s, int max) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length() && out.length() < max; i++) {
            char c = s.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString();
    }
}
