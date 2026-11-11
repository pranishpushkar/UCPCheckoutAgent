package dev.ucplab.ucp;

import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Sends a call and applies the retry matrix of 8.2 (decisions E to H). Every in-call retry of the whole system lives
 * here: the orchestrator never retries a QUOTING call (7.2).
 *
 * <p>"Up to 3" is three retries, so at most four attempts. Per operation:
 * <ul>
 *   <li>get, create, update, cancel: {@code NotSent} and {@code Uncertain} are retried, with the same key and body;
 *   <li>complete: {@code NotSent} is retried (nothing reached the merchant); {@code Uncertain}, including a 429 or 503
 *       (the classifier makes it so), is exactly one call, because a retry could charge twice and the intent must
 *       reconcile instead;
 *   <li>429 or 503 as a {@code ProtocolError}: with {@code Retry-After} of at most the backoff cap (2 s) the executor
 *       sleeps that long and retries; with a longer one it stops and returns the {@code ProtocolError} with its
 *       {@code retryAfter}, so the caller can schedule; with none it backs off;
 *   <li>everything else ({@code Ok}, {@code Rejected}, other {@code ProtocolError}) is returned at once.
 * </ul>
 * Backoff is full jitter: before retry k the delay is uniform in [0, min(cap, initial x 2^(k-1))], drawn from the
 * injected seeded generator. A call the {@link Gate} refuses is {@code NotSent} at once, without retries, unless an
 * earlier attempt was already {@code Uncertain}: then the whole call is {@code Uncertain} (see {@link #tainted}).
 *
 * <p>Each attempt has its own {@code Request-Id} (UUID-formatted, from the injected {@link IdGenerator}); the
 * {@code Idempotency-Key} and the body bytes are identical on every attempt. The executor never logs.
 */
public final class RetryExecutor {

    private final UcpConfig config;
    private final HttpTransport transport;
    private final FailureClassifier classifier;
    private final Gate gate;
    private final Sleeper sleeper;
    private final RandomGenerator random;
    private final IdGenerator ids;

    public RetryExecutor(
            UcpConfig config, HttpTransport transport, FailureClassifier classifier, Gate gate, Sleeper sleeper,
            RandomGenerator random, IdGenerator ids) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.random = Objects.requireNonNull(random, "random");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /** The result of {@link #fetch}: the reply of a 200 or 201, or the final failure outcome. */
    public sealed interface Fetched {

        record Body(HttpReply reply) implements Fetched {}

        record Failed(CallOutcome<CheckoutView> outcome) implements Fetched {}
    }

    private sealed interface Attempt {

        record Result(CallOutcome<CheckoutView> outcome) implements Attempt {}

        record Replied(HttpReply reply) implements Attempt {}
    }

    /** Sends a call whose 200 or 201 body is a checkout, and applies the matrix of 8.2. */
    public CallOutcome<CheckoutView> execute(ApiCall call) {
        Objects.requireNonNull(call, "call");
        return ((Attempt.Result) loop(call, false)).outcome();
    }

    /**
     * Sends a call whose 200 or 201 body is not a checkout (the discovery profile) with the same retry rules, and
     * returns that reply as it came; any other result is classified as in {@link #execute}.
     */
    public Fetched fetch(ApiCall call) {
        Objects.requireNonNull(call, "call");
        return switch (loop(call, true)) {
            case Attempt.Replied r -> new Fetched.Body(r.reply());
            case Attempt.Result r -> new Fetched.Failed(r.outcome());
        };
    }

    private Attempt loop(ApiCall call, boolean raw) {
        int maxAttempts = config.maxRetries() + 1;
        String firstUncertain = null;
        for (int attempt = 1;; attempt++) {
            if (!call.bypassGate() && !gate.tryAcquire()) {
                return tainted(new Attempt.Result(FailureClassifier.circuitOpen(call.hostPort())), firstUncertain, attempt);
            }
            Attempt result = once(call, raw);
            if (!call.bypassGate()) {
                switch (result) {
                    case Attempt.Result r -> gate.record(r.outcome());
                    case Attempt.Replied r -> gate.recordReplied();
                }
            }
            if (!(result instanceof Attempt.Result r)) {
                return result;
            }
            if (firstUncertain == null && r.outcome() instanceof CallOutcome.Uncertain<CheckoutView> u) {
                firstUncertain = u.cause();
            }
            Optional<Duration> wait = attempt >= maxAttempts ? Optional.empty() : retryDelay(call.op(), r.outcome(), attempt);
            if (wait.isEmpty()) {
                return tainted(result, firstUncertain, attempt);
            }
            try {
                sleeper.sleep(wait.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return tainted(result, firstUncertain, attempt);
            }
        }
    }

    /**
     * Once any attempt of a call was Uncertain, the request may have been processed, so the call can never end as
     * NotSent or ProtocolError (8.2): it is Uncertain, naming the first Uncertain attempt and the last outcome. Ok and
     * Rejected are real answers under the same key and stay as they are. A lone Uncertain attempt is returned unchanged.
     */
    private static Attempt tainted(Attempt result, String firstUncertain, int attempt) {
        if (firstUncertain == null || !(result instanceof Attempt.Result r)) {
            return result;
        }
        CallOutcome<CheckoutView> last = r.outcome();
        String lastText = switch (last) {
            case CallOutcome.Ok<CheckoutView> ok -> null;
            case CallOutcome.Rejected<CheckoutView> rej -> null;
            case CallOutcome.NotSent<CheckoutView> n -> "not sent: " + n.cause();
            case CallOutcome.Uncertain<CheckoutView> u -> u.cause();
            case CallOutcome.ProtocolError<CheckoutView> e -> "http " + e.httpStatus() + " " + e.code();
        };
        if (lastText == null || (attempt == 1 && last instanceof CallOutcome.Uncertain<CheckoutView>)) {
            return result;
        }
        return new Attempt.Result(new CallOutcome.Uncertain<>(
                "an earlier attempt was uncertain (" + firstUncertain + "); last attempt: " + lastText));
    }

    /** The delay before the next attempt, or empty if this outcome is final. {@code attempt} counts from 1. */
    private Optional<Duration> retryDelay(AttemptOp op, CallOutcome<CheckoutView> outcome, int attempt) {
        return switch (outcome) {
            case CallOutcome.NotSent<CheckoutView> n -> Optional.of(backoff(attempt));
            case CallOutcome.Uncertain<CheckoutView> u -> op == AttemptOp.COMPLETE
                    ? Optional.empty() : Optional.of(backoff(attempt));
            case CallOutcome.ProtocolError<CheckoutView> e when op != AttemptOp.COMPLETE
                    && (e.httpStatus() == 429 || e.httpStatus() == 503) -> {
                if (e.retryAfter().isEmpty()) {
                    yield Optional.of(backoff(attempt));
                }
                Duration after = e.retryAfter().get();
                yield after.compareTo(config.backoffCap()) <= 0 ? Optional.of(after) : Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    /** Full jitter before retry {@code retry} (1 for the first retry): uniform in [0, min(cap, initial x 2^(retry-1))]. */
    private Duration backoff(int retry) {
        long ceiling = config.backoffInitial().toMillis();
        for (int i = 1; i < retry && ceiling < config.backoffCap().toMillis(); i++) {
            ceiling *= 2;
        }
        ceiling = Math.min(ceiling, config.backoffCap().toMillis());
        return Duration.ofMillis(random.nextLong(ceiling + 1));
    }

    private Attempt once(ApiCall call, boolean raw) {
        HttpCall http = new HttpCall(call.method(), call.uri(), headers(call), call.body(),
                call.op() == AttemptOp.COMPLETE ? config.completeTimeout() : config.requestTimeout());
        try {
            HttpReply reply = transport.send(http);
            if (raw && (reply.status() == 200 || reply.status() == 201)) {
                return new Attempt.Replied(reply);
            }
            return new Attempt.Result(classifier.classify(call.op(), reply));
        } catch (IOException | RuntimeException e) {
            return new Attempt.Result(classifier.classify(call.op(), e));
        }
    }

    private Map<String, String> headers(ApiCall call) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("UCP-Agent", config.ucpAgent());
        h.put("Request-Id", requestId());
        h.put("Accept", "application/json");
        call.key().ifPresent(k -> h.put("Idempotency-Key", k.value()));
        if (call.body().isPresent()) {
            h.put("Content-Type", "application/json");
        }
        return h;
    }

    /** UUID-formatted text from the injected generator: used as is if it already is a UUID, else derived from it. */
    private String requestId() {
        String next = ids.next();
        try {
            return UUID.fromString(next).toString();
        } catch (IllegalArgumentException notAUuid) {
            return UUID.nameUUIDFromBytes(next.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }
}
