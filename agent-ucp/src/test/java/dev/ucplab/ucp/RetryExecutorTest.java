package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetryExecutorTest {

    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    private static final URI URL = URI.create("http://127.0.0.1:9/ucp/v1/checkout-sessions");
    private static final IdempotencyKey KEY = new IdempotencyKey("11111111-2222-8333-8444-555555555555");
    private static final FrozenBody BODY = new FrozenBody("{\"payment\":{\"instruments\":[{\"credential\":{\"token\":\"tok_ok\"}}]}}");
    private static final List<AttemptOp> RETRYING = List.of(AttemptOp.GET, AttemptOp.CREATE, AttemptOp.UPDATE,
            AttemptOp.CANCEL);

    private static byte[] fixture(String name) {
        try (InputStream in = RetryExecutorTest.class.getResourceAsStream("/merchant/" + name + ".json")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Plays back a script of replies and failures, and records every call it receives. */
    private static final class ScriptedTransport implements HttpTransport {
        final Deque<Object> script = new ArrayDeque<>();
        final List<HttpCall> calls = new ArrayList<>();

        ScriptedTransport then(Object... steps) {
            script.addAll(List.of(steps));
            return this;
        }

        @Override
        public HttpReply send(HttpCall call) throws IOException {
            calls.add(call);
            Object step = script.removeFirst();
            if (step instanceof IOException e) {
                throw e;
            }
            if (step instanceof RuntimeException e) {
                throw e;
            }
            return (HttpReply) step;
        }
    }

    private static HttpReply ok() {
        return new HttpReply(200, Map.of(), fixture("get_ready"));
    }

    private static HttpReply status(int status, String... headers) {
        Map<String, List<String>> h = new java.util.LinkedHashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            h.put(headers[i].toLowerCase(), List.of(headers[i + 1]));
        }
        return new HttpReply(status, h, fixture("error_not_found"));
    }

    private static final IOException NOT_SENT = new ConnectException("refused");
    private static final IOException UNCERTAIN = new HttpTimeoutException("timed out");

    private final List<Duration> sleeps = new ArrayList<>();
    private final ScriptedTransport transport = new ScriptedTransport();

    private RetryExecutor executor(UcpConfig config, Gate gate) {
        return new RetryExecutor(config, transport, new FailureClassifier(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
                gate, sleeps::add, new SplittableRandom(42), new SequentialIdGenerator("req-"));
    }

    private RetryExecutor executor() {
        return executor(CONFIG, Gate.NONE);
    }

    private static ApiCall call(AttemptOp op) {
        boolean get = op == AttemptOp.GET;
        return new ApiCall(op, get ? "GET" : op == AttemptOp.UPDATE ? "PUT" : "POST", URL,
                get ? Optional.empty() : Optional.of(KEY), get ? Optional.empty() : Optional.of(BODY), false);
    }

    // ---------------------------------------------------------------- matrix: retried operations

    // Covers: 8.2, decision E (get, create, update, cancel: NotSent is retried, up to 3 retries, so 4 attempts), F
    @Test
    void execute_retriesNotSentUpToThreeTimes_forGetCreateUpdateCancel() {
        for (AttemptOp op : RETRYING) {
            transport.calls.clear();
            sleeps.clear();
            transport.then(NOT_SENT, NOT_SENT, NOT_SENT, ok());

            CallOutcome<CheckoutView> outcome = executor().execute(call(op));

            assertThat(outcome).as(op.name()).isInstanceOf(CallOutcome.Ok.class);
            assertThat(transport.calls).as(op.name()).hasSize(4);
            assertThat(sleeps).as(op.name()).hasSize(3);
        }
    }

    // Covers: 8.2 (the fourth failure ends it: NotSent is returned after 4 attempts and 3 sleeps)
    @Test
    void execute_returnsLastNotSent_afterFourAttempts() {
        transport.then(NOT_SENT, NOT_SENT, NOT_SENT, NOT_SENT);

        CallOutcome<CheckoutView> outcome = executor().execute(call(AttemptOp.CREATE));

        assertThat(outcome).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(transport.calls).hasSize(4);
        assertThat(sleeps).hasSize(3);
    }

    // Covers: 8.2 (get, create, update, cancel: Uncertain is retried too, up to 4 attempts)
    @Test
    void execute_retriesUncertainUpToThreeTimes_forGetCreateUpdateCancel() {
        for (AttemptOp op : RETRYING) {
            transport.calls.clear();
            sleeps.clear();
            transport.then(UNCERTAIN, status(500), UNCERTAIN, UNCERTAIN);

            CallOutcome<CheckoutView> outcome = executor().execute(call(op));

            assertThat(outcome).as(op.name()).isInstanceOf(CallOutcome.Uncertain.class);
            assertThat(transport.calls).as(op.name()).hasSize(4);
            assertThat(sleeps).as(op.name()).hasSize(3);
        }
    }

    // ---------------------------------------------------------------- matrix: complete

    // Covers: 8.2, decision E (complete: NotSent is retried, up to 4 attempts, since nothing reached the merchant)
    @Test
    void execute_retriesCompleteOnNotSent_upToFourAttempts() {
        transport.then(NOT_SENT, NOT_SENT, NOT_SENT, ok());

        assertThat(executor().execute(call(AttemptOp.COMPLETE))).isInstanceOf(CallOutcome.Ok.class);
        assertThat(transport.calls).hasSize(4);
    }

    // Covers: 8.2 (complete NotSent four times: NotSent comes back, still 4 attempts)
    @Test
    void execute_returnsNotSent_whenCompleteNeverGetsThrough() {
        transport.then(NOT_SENT, NOT_SENT, NOT_SENT, NOT_SENT);

        assertThat(executor().execute(call(AttemptOp.COMPLETE))).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(transport.calls).hasSize(4);
    }

    // Covers: 8.2 (complete: Uncertain, from any cause, is exactly one call and is never retried, so the intent goes
    // to RECONCILING; this includes a timeout, a 500, a 504, an unreadable body and 429 or 503, decision D)
    @Test
    void execute_makesExactlyOneCall_whenCompleteIsUncertain() {
        List<Object> uncertain = List.of(UNCERTAIN, new IOException("reset"), status(500), status(504),
                status(429, "Retry-After", "1"), status(503), new HttpReply(200, Map.of(), "<html>".getBytes(
                        StandardCharsets.UTF_8)), new IllegalStateException("boom"));
        for (Object step : uncertain) {
            transport.calls.clear();
            sleeps.clear();
            transport.then(step);

            CallOutcome<CheckoutView> outcome = executor().execute(call(AttemptOp.COMPLETE));

            assertThat(outcome).as(String.valueOf(step)).isInstanceOf(CallOutcome.Uncertain.class);
            assertThat(transport.calls).as(String.valueOf(step)).hasSize(1);
            assertThat(sleeps).isEmpty();
        }
    }

    // ---------------------------------------------------------------- matrix: 429 and 503, Retry-After (decision G)

    // Covers: 8.2, decision G (429 or 503 with Retry-After of at most 2 s: sleep exactly that long and retry)
    @Test
    void execute_sleepsRetryAfterAndRetries_whenItIsAtMostTwoSeconds() {
        for (AttemptOp op : RETRYING) {
            for (int status : new int[] {429, 503}) {
                transport.calls.clear();
                sleeps.clear();
                transport.then(status(status, "Retry-After", "1"), status(status, "Retry-After", "2"), ok());

                assertThat(executor().execute(call(op))).as(op + " " + status).isInstanceOf(CallOutcome.Ok.class);
                assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
                assertThat(transport.calls).hasSize(3);
            }
        }
    }

    // Covers: 8.2, decision G (Retry-After above 2 s: stop at once and return the ProtocolError with retryAfter, so
    // the caller can schedule; no sleep, one call)
    @Test
    void execute_stopsWithProtocolError_whenRetryAfterExceedsTheBackoffCap() {
        transport.then(status(429, "Retry-After", "3"));

        CallOutcome<CheckoutView> outcome = executor().execute(call(AttemptOp.GET));

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(429);
            assertThat(e.retryAfter()).contains(Duration.ofSeconds(3));
        });
        assertThat(transport.calls).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    // Covers: 8.2 (429 or 503 with no Retry-After: retried with the normal backoff; after 4 attempts the ProtocolError
    // is returned)
    @Test
    void execute_backsOffAndRetries_whenNoRetryAfter_thenReturnsProtocolError() {
        transport.then(status(503), status(503), status(503), status(503));

        CallOutcome<CheckoutView> outcome = executor().execute(call(AttemptOp.UPDATE));

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.httpStatus()).isEqualTo(503));
        assertThat(transport.calls).hasSize(4);
        assertThat(sleeps).hasSize(3);
    }

    // Covers: 8.2 (a Retry-After of 2 s on the last attempt does not sleep: there is no further attempt)
    @Test
    void execute_doesNotSleepAfterTheLastAttempt() {
        transport.then(status(429, "Retry-After", "1"), status(429, "Retry-After", "1"), status(429, "Retry-After", "1"),
                status(429, "Retry-After", "1"));

        executor().execute(call(AttemptOp.CREATE));

        assertThat(transport.calls).hasSize(4);
        assertThat(sleeps).hasSize(3);
    }

    // ---------------------------------------------------------------- nothing else is retried

    // Covers: 8.1, 8.2 (Ok, Rejected and non-retryable ProtocolErrors are returned after one call)
    @Test
    void execute_returnsAtOnce_forOkRejectedAndClientErrors() {
        for (int status : new int[] {400, 401, 403, 404, 409, 422}) {
            transport.calls.clear();
            transport.then(status(status));

            assertThat(executor().execute(call(AttemptOp.GET))).isInstanceOf(CallOutcome.ProtocolError.class);
            assertThat(transport.calls).hasSize(1);
        }
        transport.calls.clear();
        transport.then(new HttpReply(200, Map.of(), fixture("create_all_unavailable")));

        assertThat(executor().execute(call(AttemptOp.CREATE))).isInstanceOf(CallOutcome.Rejected.class);
        assertThat(transport.calls).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    // ---------------------------------------------------------------- headers, keys and bytes (H, I7)

    // Covers: 8.4, decision H, I7 (every attempt carries the same Idempotency-Key and the same body bytes; each has its
    // own Request-Id, UUID-formatted; UCP-Agent comes from the configuration; there is no Authorization header)
    @Test
    void execute_sendsSameKeyAndBytes_withAFreshUuidRequestIdPerAttempt() {
        transport.then(NOT_SENT, UNCERTAIN, status(503), ok());

        executor().execute(call(AttemptOp.UPDATE));

        assertThat(transport.calls).hasSize(4);
        assertThat(transport.calls).allSatisfy(c -> {
            assertThat(c.method()).isEqualTo("PUT");
            assertThat(c.headers()).containsEntry("Idempotency-Key", KEY.value())
                    .containsEntry("UCP-Agent", CONFIG.ucpAgent())
                    .containsEntry("Content-Type", "application/json").containsEntry("Accept", "application/json")
                    .doesNotContainKey("Authorization");
            assertThat(c.body().orElseThrow().bytes()).isEqualTo(BODY.bytes());
            assertThat(UUID.fromString(c.headers().get("Request-Id"))).isNotNull();
        });
        assertThat(transport.calls.stream().map(c -> c.headers().get("Request-Id")).distinct()).hasSize(4);
    }

    // Covers: 8.4 (a get has no Idempotency-Key, no body and no Content-Type; complete has the 10 s timeout, the rest 5 s)
    @Test
    void execute_buildsGetWithoutKeyOrBody_andUsesPerOperationTimeouts() {
        transport.then(ok(), ok());

        executor().execute(call(AttemptOp.GET));
        executor().execute(call(AttemptOp.COMPLETE));

        HttpCall get = transport.calls.get(0);
        assertThat(get.headers()).doesNotContainKeys("Idempotency-Key", "Content-Type");
        assertThat(get.body()).isEmpty();
        assertThat(get.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(transport.calls.get(1).timeout()).isEqualTo(Duration.ofSeconds(10));
    }

    // ---------------------------------------------------------------- backoff

    // Covers: 8.2 (full jitter: before retry k the delay is uniform in [0, min(cap, 200 ms x 2^(k-1))], drawn from the
    // seeded generator, so the same seed gives the same delays)
    @Test
    void execute_backoff_isFullJitterFromTheSeededGenerator() {
        transport.then(NOT_SENT, NOT_SENT, NOT_SENT, NOT_SENT);

        executor().execute(call(AttemptOp.GET));

        SplittableRandom same = new SplittableRandom(42);
        assertThat(sleeps).containsExactly(Duration.ofMillis(same.nextLong(201)), Duration.ofMillis(same.nextLong(401)),
                Duration.ofMillis(same.nextLong(801)));
    }

    // Covers: 8.2 (the ceilings double from 200 ms and stop at the 2 s cap)
    @Test
    void execute_backoffCeilings_doubleAndStopAtTheCap() {
        UcpConfig seven = new UcpConfig(CONFIG.ucpAgent(), CONFIG.connectTimeout(), CONFIG.requestTimeout(),
                CONFIG.completeTimeout(), 6, CONFIG.backoffInitial(), CONFIG.backoffCap(), CONFIG.breakerThreshold(),
                CONFIG.breakerOpenFor(), CONFIG.maxResponseBytes());
        transport.then(NOT_SENT, NOT_SENT, NOT_SENT, NOT_SENT, NOT_SENT, NOT_SENT, NOT_SENT);

        executor(seven, Gate.NONE).execute(call(AttemptOp.GET));

        SplittableRandom same = new SplittableRandom(42);
        long[] ceilings = {200, 400, 800, 1600, 2000, 2000};
        List<Duration> expected = new ArrayList<>();
        for (long c : ceilings) {
            expected.add(Duration.ofMillis(same.nextLong(c + 1)));
        }
        assertThat(sleeps).containsExactlyElementsOf(expected);
    }

    // Covers: 7.8 (an interrupt during a sleep stops the retries and returns what was last observed, interrupt flag kept)
    @Test
    void execute_stopsOnInterrupt_andKeepsTheInterruptFlag() {
        transport.then(NOT_SENT, ok());
        RetryExecutor interrupted = new RetryExecutor(CONFIG, transport,
                new FailureClassifier(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)), Gate.NONE, d -> {
                    throw new InterruptedException();
                }, new SplittableRandom(1), new SequentialIdGenerator("req-"));

        CallOutcome<CheckoutView> outcome = interrupted.execute(call(AttemptOp.GET));

        assertThat(outcome).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(transport.calls).hasSize(1);
        assertThat(Thread.interrupted()).isTrue();
    }

    // ---------------------------------------------------------------- gate (circuit breaker)

    private static final class CountingGate implements Gate {
        boolean open = true;
        int acquired;
        final List<CallOutcome<?>> recorded = new ArrayList<>();

        @Override
        public boolean tryAcquire() {
            acquired++;
            return open;
        }

        @Override
        public void record(CallOutcome<?> outcome) {
            recorded.add(outcome);
        }
    }

    // Covers: 8.3, decision I (a gate that refuses gives NotSent at once: nothing is sent, nothing is retried or slept)
    @Test
    void execute_returnsNotSentWithoutSending_whenTheGateRefuses() {
        CountingGate gate = new CountingGate();
        gate.open = false;

        CallOutcome<CheckoutView> outcome = executor(CONFIG, gate).execute(call(AttemptOp.CREATE));

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                n -> assertThat(n.cause()).contains("circuit breaker open"));
        assertThat(transport.calls).isEmpty();
        assertThat(sleeps).isEmpty();
        assertThat(gate.recorded).isEmpty();
    }

    // Covers: 8.3, decision I (every attempt acquires the gate and records its outcome; a get marked as bypassing, which
    // is how reconciliation reads are sent, never touches it)
    @Test
    void execute_recordsEachAttemptOnTheGate_andBypassSkipsIt() {
        CountingGate gate = new CountingGate();
        transport.then(NOT_SENT, ok(), ok());

        executor(CONFIG, gate).execute(call(AttemptOp.CREATE));
        assertThat(gate.acquired).isEqualTo(2);
        assertThat(gate.recorded).hasSize(2);
        assertThat(gate.recorded.get(0)).isInstanceOf(CallOutcome.NotSent.class);

        gate.open = false;
        ApiCall bypass = new ApiCall(AttemptOp.GET, "GET", URL, Optional.empty(), Optional.empty(), true);

        assertThat(executor(CONFIG, gate).execute(bypass)).isInstanceOf(CallOutcome.Ok.class);
        assertThat(gate.acquired).isEqualTo(2);
        assertThat(gate.recorded).hasSize(2);
    }
}
