package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.CheckoutView;
import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    private static final CallOutcome<CheckoutView> NOT_SENT = new CallOutcome.NotSent<>("refused");
    private static final CallOutcome<CheckoutView> UNCERTAIN = new CallOutcome.Uncertain<>("timeout");

    private static CallOutcome<CheckoutView> protocol(int status) {
        return new CallOutcome.ProtocolError<>(status, "x", "x", Optional.empty());
    }

    private final TestClock clock = new TestClock();
    private final CircuitBreaker breaker = new CircuitBreaker(CONFIG, clock);

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.record(NOT_SENT);
        }
    }

    // Covers: 8.3, decision I (opens after 5 consecutive failures, not before)
    @Test
    void opensAfterFiveConsecutiveFailures() {
        fail(4);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();

        breaker.record(NOT_SENT);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    // Covers: 8.3 (a success resets the count: failures must be consecutive)
    @Test
    void aSuccessResetsTheConsecutiveCount() {
        fail(4);
        breaker.tryAcquire();
        breaker.record(new CallOutcome.Rejected<>(200, List.of()));
        fail(4);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // Covers: 8.3, decision I (failures are NotSent, Uncertain and 5xx, including 503)
    @Test
    void countsNotSentUncertainAndFiveHundreds_asFailures() {
        for (CallOutcome<CheckoutView> failure : List.of(NOT_SENT, UNCERTAIN, protocol(500), protocol(502),
                protocol(503), protocol(599))) {
            CircuitBreaker b = new CircuitBreaker(CONFIG, clock);
            for (int i = 0; i < 5; i++) {
                b.tryAcquire();
                b.record(failure);
            }
            assertThat(b.state()).as(String.valueOf(failure)).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    // Covers: 8.3, decision I (not failures: Ok, Rejected, a 4xx, a 429 and any business message; the host answered, so
    // they reset the count)
    @Test
    void doesNotCountOkRejectedClientErrorsOr429() {
        for (CallOutcome<CheckoutView> fine : List.of(new CallOutcome.Rejected<CheckoutView>(200, List.of()),
                protocol(400), protocol(401), protocol(404), protocol(409), protocol(422), protocol(429))) {
            CircuitBreaker b = new CircuitBreaker(CONFIG, clock);
            for (int i = 0; i < 20; i++) {
                assertThat(b.tryAcquire()).isTrue();
                b.record(fine);
            }
            assertThat(b.state()).as(String.valueOf(fine)).isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    // Covers: 8.3 (stays open for 30 s, then one probe: the second caller is refused while the probe is out)
    @Test
    void staysOpenFor30Seconds_thenAllowsExactlyOneProbe() {
        fail(5);
        clock.advance(Duration.ofSeconds(29));
        assertThat(breaker.tryAcquire()).isFalse();

        clock.advance(Duration.ofSeconds(1));

        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    // Covers: 8.3 (a successful probe closes the breaker and clears the count)
    @Test
    void successfulProbe_closesTheBreaker() {
        fail(5);
        clock.advance(Duration.ofSeconds(30));
        breaker.tryAcquire();

        breaker.record(new CallOutcome.Rejected<>(200, List.of()));

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
        fail(4);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // Covers: 8.3 (a failed probe re-opens it for another full 30 s from the probe's failure)
    @Test
    void failedProbe_reopensForAnother30Seconds() {
        fail(5);
        clock.advance(Duration.ofSeconds(30));
        breaker.tryAcquire();
        clock.advance(Duration.ofSeconds(2));

        breaker.record(UNCERTAIN);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.advance(Duration.ofSeconds(29));
        assertThat(breaker.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.tryAcquire()).isTrue();
    }

    // Covers: 8.3 (a probe whose result is never recorded, for example a crashed caller, does not block the host
    // forever: after another 30 s a new probe is allowed)
    @Test
    void aLostProbe_isReplacedAfterAnotherOpenPeriod() {
        fail(5);
        clock.advance(Duration.ofSeconds(30));
        assertThat(breaker.tryAcquire()).isTrue();
        clock.advance(Duration.ofSeconds(29));
        assertThat(breaker.tryAcquire()).isFalse();

        clock.advance(Duration.ofSeconds(1));

        assertThat(breaker.tryAcquire()).isTrue();
    }

    // Covers: 8.3 (a result that arrives late, after the breaker opened, does not change an open breaker)
    @Test
    void aLateResult_doesNotCloseAnOpenBreaker() {
        fail(5);

        breaker.record(new CallOutcome.Rejected<>(200, List.of()));

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    // Covers: 8.3, 19 (one breaker per merchant: two merchants are independent, and the same merchant always gets the
    // same breaker)
    @Test
    void registry_keepsOneBreakerPerMerchant() {
        CircuitBreakers registry = new CircuitBreakers(CONFIG, clock);

        CircuitBreaker a = registry.forMerchant(new MerchantId("shop-1"));
        CircuitBreaker b = registry.forMerchant(new MerchantId("shop-2"));
        for (int i = 0; i < 5; i++) {
            a.tryAcquire();
            a.record(NOT_SENT);
        }

        assertThat(registry.forMerchant(new MerchantId("shop-1"))).isSameAs(a);
        assertThat(a.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.tryAcquire()).isTrue();
    }

    // Covers: 8.3, decision I (through the executor: five failed calls open it, the sixth is NotSent without touching the
    // transport, and a reconciliation read that bypasses the breaker still goes out)
    @Test
    void withTheExecutor_opensAfterFiveFailures_andReconciliationReadsBypass() {
        AtomicInteger sent = new AtomicInteger();
        UcpConfig noRetries = new UcpConfig(CONFIG.ucpAgent(), CONFIG.connectTimeout(), CONFIG.requestTimeout(),
                CONFIG.completeTimeout(), 0, CONFIG.backoffInitial(), CONFIG.backoffCap(), CONFIG.breakerThreshold(),
                CONFIG.breakerOpenFor(), CONFIG.maxResponseBytes());
        HttpTransport down = call -> {
            sent.incrementAndGet();
            throw new ConnectException("refused");
        };
        RetryExecutor executor = new RetryExecutor(noRetries, down, new FailureClassifier(clock), breaker, d -> { },
                new SplittableRandom(1), new SequentialIdGenerator("r"));
        ApiCall create = new ApiCall(AttemptOp.CREATE, "POST", URI.create("http://127.0.0.1:9/ucp/v1/checkout-sessions"),
                Optional.of(new dev.ucplab.core.merchant.IdempotencyKey("11111111-2222-8333-8444-555555555555")),
                Optional.of(new dev.ucplab.core.merchant.FrozenBody("{}")), false);
        for (int i = 0; i < 5; i++) {
            executor.execute(create);
        }
        assertThat(sent).hasValue(5);

        assertThat(executor.execute(create)).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                n -> assertThat(n.cause()).contains("circuit breaker open for 127.0.0.1:9"));
        assertThat(sent).hasValue(5);

        executor.execute(new ApiCall(AttemptOp.GET, "GET", URI.create("http://127.0.0.1:9/ucp/v1/checkout-sessions/x"),
                Optional.empty(), Optional.empty(), true));
        assertThat(sent).hasValue(6);
    }

    // Covers: 7.8 (no lock is held across anything slow; many threads can acquire and record without deadlock or a
    // lost state change)
    @Test
    void isSafeUnderConcurrentUse() throws Exception {
        List<Thread> threads = new java.util.ArrayList<>();
        for (int t = 0; t < 50; t++) {
            threads.add(Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 200; i++) {
                    if (breaker.tryAcquire()) {
                        breaker.record(NOT_SENT);
                    }
                }
            }));
        }
        for (Thread t : threads) {
            t.join(5000);
        }

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
