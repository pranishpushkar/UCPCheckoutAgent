package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.id.IdGenerator;
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
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/** H3 P8: the retry matrix of 8.2 and the identity of every attempt (8.4, I7), one test per mutant it kills. */
class RetryExecutorMutationTest {

    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    private static final URI URL = URI.create("http://127.0.0.1:9/ucp/v1/checkout-sessions");
    private static final IdempotencyKey KEY = new IdempotencyKey("11111111-2222-8333-8444-555555555555");
    private static final FrozenBody BODY = new FrozenBody("{\"payment\":{\"token\":\"tok_ok\"}}");
    private static final IOException UNCERTAIN_A = new HttpTimeoutException("timed out");
    private static final IOException UNCERTAIN_B = new IOException("connection reset");
    private static final IOException UNCERTAIN_C = new IOException("broken pipe");
    private static final IOException UNCERTAIN_D = new IOException("eof");
    private static final IOException NOT_SENT = unresolved();

    private static ConnectException unresolved() {
        ConnectException e = new ConnectException("unresolved");
        e.initCause(new java.nio.channels.UnresolvedAddressException());
        return e;
    }

    private static byte[] fixture(String name) {
        try (InputStream in = RetryExecutorMutationTest.class.getResourceAsStream("/merchant/" + name + ".json")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpReply reply(int status, String fixture) {
        return new HttpReply(status, Map.of(), fixture(fixture));
    }

    /** Plays a script, then fails the test (an Error: the executor catches only exceptions) once it is asked for too much. */
    private static final class Transport implements HttpTransport {
        final Deque<Object> script = new ArrayDeque<>();
        final List<HttpCall> calls = new ArrayList<>();
        final Object repeat;
        final int limit;

        Transport(Object repeat, int limit) {
            this.repeat = repeat;
            this.limit = limit;
        }

        Transport then(Object... steps) {
            script.addAll(List.of(steps));
            return this;
        }

        @Override
        public HttpReply send(HttpCall call) throws IOException {
            calls.add(call);
            if (calls.size() > limit) {
                throw new AssertionError("more than " + limit + " attempts");
            }
            Object step = script.isEmpty() ? repeat : script.removeFirst();
            if (step instanceof IOException e) {
                throw e;
            }
            return (HttpReply) step;
        }
    }

    /** Draws the top of every range: the delay is then exactly the ceiling the executor computed. */
    private static final RandomGenerator MAX_JITTER = new RandomGenerator() {
        @Override
        public long nextLong() {
            return Long.MAX_VALUE;
        }

        @Override
        public long nextLong(long bound) {
            return bound - 1;
        }
    };

    private final List<Duration> sleeps = new ArrayList<>();

    private RetryExecutor executor(UcpConfig config, HttpTransport transport, Gate gate, IdGenerator ids) {
        return new RetryExecutor(config, transport, new FailureClassifier(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)), gate,
                sleeps::add, MAX_JITTER, ids);
    }

    private RetryExecutor executor(UcpConfig config, HttpTransport transport) {
        return executor(config, transport, Gate.NONE, new SequentialIdGenerator("req-"));
    }

    private static ApiCall call(AttemptOp op) {
        boolean get = op == AttemptOp.GET;
        return new ApiCall(op, get ? "GET" : "POST", URL, get ? Optional.empty() : Optional.of(KEY),
                get ? Optional.empty() : Optional.of(BODY), false);
    }

    private static UcpConfig withRetries(int retries) {
        return new UcpConfig(CONFIG.ucpAgent(), CONFIG.connectTimeout(), CONFIG.requestTimeout(), CONFIG.completeTimeout(),
                retries, Duration.ofMillis(200), Duration.ofSeconds(2), 5, CONFIG.breakerOpenFor(), CONFIG.maxResponseBytes());
    }

    // Covers: 8.2 (full jitter: before retry k the delay is up to min(cap, initial x 2^(k-1))). Kills RetryExecutor.backoff:172
    // ConditionalsBoundary (the first retry would wait up to 2 x initial) and RemoveConditional_ORDER_IF
    @Test
    void theBackoffBeforeRetryK_isInitialTimesTwoToTheKMinusOne_upToTheCap() {
        Transport transport = new Transport(NOT_SENT, 20);

        executor(withRetries(6), transport).execute(call(AttemptOp.GET));

        assertThat(sleeps).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400), Duration.ofMillis(800),
                Duration.ofMillis(1600), Duration.ofMillis(2000), Duration.ofMillis(2000));
    }

    // Covers: 8.2. Kills RetryExecutor.backoff:172 RemoveConditional_ORDER_IF (the `ceiling < cap` operand): once at the cap the
    // ceiling stops doubling; without that, a long run of retries overflows the ceiling to zero and the waits vanish
    @Test
    void theBackoff_staysAtTheCap_howeverManyRetriesThereAre() {
        UcpConfig many = new UcpConfig(CONFIG.ucpAgent(), CONFIG.connectTimeout(), CONFIG.requestTimeout(),
                CONFIG.completeTimeout(), 70, Duration.ofSeconds(1), Duration.ofSeconds(2), 5, CONFIG.breakerOpenFor(),
                CONFIG.maxResponseBytes());
        Transport transport = new Transport(NOT_SENT, 100);

        executor(many, transport).execute(call(AttemptOp.GET));

        assertThat(sleeps).hasSize(70);
        assertThat(sleeps.subList(1, 70)).as("every wait after the first is the cap").containsOnly(Duration.ofSeconds(2));
    }

    // Covers: 8.2 ("up to 3 retries, so at most 4 attempts"). Kills RetryExecutor.loop:97 Increments and loop:114
    // RemoveConditional_ORDER_ELSE (both timed out: the loop never stopped): a transport that fails for ever is called four times
    @Test
    void aCallThatFailsForEver_isSentFourTimes_andThenGivesUp() {
        Transport transport = new Transport(NOT_SENT, 10);

        CallOutcome<CheckoutView> out = executor(CONFIG, transport).execute(call(AttemptOp.CREATE));

        assertThat(transport.calls).hasSize(4);
        assertThat(sleeps).hasSize(3);
        assertThat(out).isInstanceOf(CallOutcome.NotSent.class);
    }

    // Covers: 8.2, I1 (an Uncertain attempt taints the call: the final Uncertain names the FIRST uncertain attempt and the last
    // outcome). Kills RetryExecutor.loop:111 RemoveConditional_EQUAL_IF (the first cause would be overwritten by a later one)
    @Test
    void aTaintedCall_namesTheFirstUncertainAttempt() {
        Transport transport = new Transport(NOT_SENT, 10).then(UNCERTAIN_A, UNCERTAIN_B, NOT_SENT, NOT_SENT);

        CallOutcome<CheckoutView> out = executor(CONFIG, transport).execute(call(AttemptOp.GET));

        assertThat(out).isInstanceOfSatisfying(CallOutcome.Uncertain.class, u -> assertThat(u.cause())
                .startsWith("an earlier attempt was uncertain (HttpTimeoutException: timed out); last attempt: not sent: ")
                .doesNotContain("connection reset"));
    }

    // Covers: 8.2. Kills RetryExecutor.tainted:144 RemoveConditional_EQUAL_IF (`attempt == 1` forced true): a last attempt that is
    // Uncertain after earlier ones still names the earlier one
    @Test
    void aLastUncertainAfterEarlierOnes_namesBoth() {
        Transport transport = new Transport(NOT_SENT, 10).then(UNCERTAIN_A, UNCERTAIN_B, UNCERTAIN_C, UNCERTAIN_D);

        CallOutcome<CheckoutView> out = executor(CONFIG, transport).execute(call(AttemptOp.GET));

        assertThat(out).isEqualTo(new CallOutcome.Uncertain<CheckoutView>(
                "an earlier attempt was uncertain (HttpTimeoutException: timed out); last attempt: IOException: eof"));
    }

    // Covers: 8.2. Kills RetryExecutor.tainted:144 RemoveConditional_EQUAL_ELSE (`attempt == 1` forced false) and
    // `lastText == null` forced false: a lone Uncertain complete is returned as the classifier made it, not wrapped
    @Test
    void aLoneUncertainComplete_isReturnedAsClassified() {
        Transport transport = new Transport(NOT_SENT, 10).then(UNCERTAIN_A);

        CallOutcome<CheckoutView> out = executor(CONFIG, transport).execute(call(AttemptOp.COMPLETE));

        assertThat(out).isEqualTo(new CallOutcome.Uncertain<CheckoutView>("HttpTimeoutException: timed out"));
        assertThat(transport.calls).as("complete is never retried on Uncertain (I1, I7)").hasSize(1);
    }

    // Covers: 8.2, I1. Kills RetryExecutor.tainted:144 `lastText == null` (Ok and Rejected are real answers under the same key):
    // an Ok or a Rejected after an Uncertain attempt is returned as it is
    @Test
    void anAnswerAfterAnUncertainAttempt_staysAnAnswer() {
        Transport ok = new Transport(NOT_SENT, 10).then(UNCERTAIN_A, reply(200, "get_ready"));
        Transport rejected = new Transport(NOT_SENT, 10).then(UNCERTAIN_A, reply(200, "create_all_unavailable"));

        assertThat(executor(CONFIG, ok).execute(call(AttemptOp.GET))).isInstanceOf(CallOutcome.Ok.class);
        assertThat(executor(CONFIG, rejected).execute(call(AttemptOp.CREATE))).isInstanceOf(CallOutcome.Rejected.class);
    }

    // Covers: 8.2, I1. Kills RetryExecutor.tainted:133 and :144 (a ProtocolError that ends a tainted call): the call stays
    // Uncertain, naming the status and the code of the last answer
    @Test
    void aProtocolErrorAfterAnUncertainAttempt_endsUncertain() {
        Transport transport = new Transport(NOT_SENT, 10).then(UNCERTAIN_A, reply(404, "error_not_found"));

        CallOutcome<CheckoutView> out = executor(CONFIG, transport).execute(call(AttemptOp.GET));

        assertThat(out).isInstanceOfSatisfying(CallOutcome.Uncertain.class, u -> assertThat(u.cause())
                .startsWith("an earlier attempt was uncertain (HttpTimeoutException: timed out); last attempt: http 404 "));
    }

    /** A gate that admits the first {@code admit} attempts and records what it is told. */
    private static final class CountingGate implements Gate {
        final int admit;
        int acquired;
        int recorded;
        int replied;

        CountingGate(int admit) {
            this.admit = admit;
        }

        @Override
        public boolean tryAcquire() {
            return acquired++ < admit;
        }

        @Override
        public String reason() {
            return "circuit_open";
        }

        @Override
        public void record(CallOutcome<?> outcome) {
            recorded++;
        }

        @Override
        public void recordReplied() {
            replied++;
        }
    }

    // Covers: 8.3. Kills RetryExecutor.loop:105 VoidMethodCall (Gate.recordReplied): an admitted attempt whose reply is a body
    // that is not a checkout (a discovery profile) tells the gate the host is alive, and not the outcome record
    @Test
    void aFetchedProfile_tellsTheGateTheHostReplied() {
        CountingGate gate = new CountingGate(10);
        Transport transport = new Transport(NOT_SENT, 10).then(reply(200, "discovery"));

        RetryExecutor.Fetched fetched = executor(CONFIG, transport, gate, new SequentialIdGenerator("req-")).fetch(call(AttemptOp.GET));

        assertThat(fetched).isInstanceOf(RetryExecutor.Fetched.Body.class);
        assertThat(gate.replied).isEqualTo(1);
        assertThat(gate.recorded).isZero();
    }

    // Covers: 8.1 (a 200 or a 201 body that is not a checkout is returned as it came). Kills RetryExecutor.once:184
    // RemoveConditional_EQUAL_ELSE (the 201 operand)
    @Test
    void aFetchedBody_isReturned_whetherTheStatusIs200Or201() {
        for (int status : new int[] {200, 201}) {
            Transport transport = new Transport(NOT_SENT, 10).then(reply(status, "discovery"));

            RetryExecutor.Fetched fetched = executor(CONFIG, transport).fetch(call(AttemptOp.GET));

            assertThat(fetched).as("status " + status).isInstanceOf(RetryExecutor.Fetched.Body.class);
        }
        Transport other = new Transport(NOT_SENT, 10).then(reply(202, "discovery"));
        assertThat(executor(CONFIG, other).fetch(call(AttemptOp.GET))).as("status 202").isInstanceOf(RetryExecutor.Fetched.Failed.class);
    }

    // Covers: 8.3 (a checkout attempt reports its outcome to the gate, not a reply). Guards loop:104/105 from the other side
    @Test
    void anExecutedCall_reportsItsOutcomeToTheGate() {
        CountingGate gate = new CountingGate(10);
        Transport transport = new Transport(NOT_SENT, 10).then(reply(200, "get_ready"));

        executor(CONFIG, transport, gate, new SequentialIdGenerator("req-")).execute(call(AttemptOp.GET));

        assertThat(gate.recorded).isEqualTo(1);
        assertThat(gate.replied).isZero();
    }

    // Covers: 8.2, 8.3. A gate that refuses after an Uncertain attempt: the call is Uncertain, naming that attempt and the refusal
    @Test
    void aGateRefusalAfterAnUncertainAttempt_endsUncertain() {
        CountingGate gate = new CountingGate(1);
        Transport transport = new Transport(NOT_SENT, 10).then(UNCERTAIN_A);

        CallOutcome<CheckoutView> out = executor(CONFIG, transport, gate, new SequentialIdGenerator("req-")).execute(call(AttemptOp.GET));

        assertThat(out).isInstanceOfSatisfying(CallOutcome.Uncertain.class, u -> assertThat(u.cause())
                .startsWith("an earlier attempt was uncertain (HttpTimeoutException: timed out); last attempt: not sent: circuit breaker open for 127.0.0.1:9"));
        assertThat(transport.calls).hasSize(1);
    }

    // Covers: 8.4, I7. Kills RetryExecutor.requestId:209 EmptyObjectReturnVals (NO_COVERAGE): an id that already is a UUID is the
    // Request-Id as it is; any other id gives a stable UUID derived from it; every attempt has its own
    @Test
    void theRequestId_isTheGeneratorsUuid_orAUuidDerivedFromIt() {
        String uuid = "123e4567-e89b-42d3-a456-426614174000";
        Transport transport = new Transport(NOT_SENT, 10);
        executor(CONFIG, transport, Gate.NONE, () -> uuid).execute(call(AttemptOp.COMPLETE));
        assertThat(transport.calls.get(0).headers()).containsEntry("Request-Id", uuid);

        Transport other = new Transport(NOT_SENT, 10);
        executor(CONFIG, other, Gate.NONE, new SequentialIdGenerator("req-")).execute(call(AttemptOp.GET));
        assertThat(other.calls).extracting(c -> c.headers().get("Request-Id")).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(UUID.fromString(id).toString()).isEqualTo(id));
        assertThat(other.calls.get(0).headers().get("Request-Id"))
                .isEqualTo(UUID.nameUUIDFromBytes(new SequentialIdGenerator("req-").next().getBytes(StandardCharsets.UTF_8)).toString());
    }
}
