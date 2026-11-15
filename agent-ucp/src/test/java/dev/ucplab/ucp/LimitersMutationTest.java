package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** H3 P8: the circuit breaker, the rate limiter and the gate that joins them (8.3), per mutant. */
class LimitersMutationTest {

    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    private final TestClock clock = new TestClock();

    private static CallOutcome<CheckoutView> failure() {
        return new CallOutcome.NotSent<>("down");
    }

    /** Runs {@code body} on another thread: it fails, fast, when that thread cannot get a lock the first one left held. */
    private static <T> T onAnotherThread(Callable<T> body) {
        ExecutorService other = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        try {
            return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> other.submit(body).get(4, TimeUnit.SECONDS));
        } finally {
            other.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- circuit breaker

    // Covers: 8.3. Kills CircuitBreaker.recordReplied:76 VoidMethodCall: a usable reply that is not a checkout (a profile) counts as
    // the host being alive, so it resets the run of failures
    @Test
    void aReplyThatIsNotACheckout_resetsTheRunOfFailures() {
        CircuitBreaker breaker = new CircuitBreaker(CONFIG, clock);
        for (int i = 0; i < CONFIG.breakerThreshold() - 1; i++) {
            breaker.record(failure());
        }
        breaker.recordReplied();
        for (int i = 0; i < CONFIG.breakerThreshold() - 1; i++) {
            breaker.record(failure());
        }

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        breaker.record(failure());
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // Covers: 8.3. A reply that is not a checkout closes a half-open breaker whose probe it was
    @Test
    void aProfileReply_closesAHalfOpenBreaker() {
        CircuitBreaker breaker = new CircuitBreaker(CONFIG, clock);
        for (int i = 0; i < CONFIG.breakerThreshold(); i++) {
            breaker.record(failure());
        }
        clock.advance(CONFIG.breakerOpenFor());
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        breaker.recordReplied();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // Covers: 7.8, 8.3. Kills CircuitBreaker.record:102, state:116 and tryAcquire:64 VoidMethodCall (all TIMED_OUT: the lock is never
    // released, so the next thread waits for ever): after each of them, another thread gets through
    @Test
    void theBreakersLock_isReleasedByEveryMethod() {
        CircuitBreaker breaker = new CircuitBreaker(CONFIG, clock);
        breaker.tryAcquire();
        assertThat(onAnotherThread(breaker::tryAcquire)).isTrue();
        breaker.record(failure());
        assertThat(onAnotherThread(() -> breaker.state())).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(onAnotherThread(() -> {
            breaker.record(failure());
            return breaker.state();
        })).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(onAnotherThread(() -> {
            breaker.recordReplied();
            return breaker.tryAcquire();
        })).isTrue();
    }

    // ---------------------------------------------------------------- rate limiter

    // Covers: 8.3. Kills RateLimiter.<init>:33 and :36 (RemoveConditional_ORDER_ELSE, ConditionalsBoundary): a burst below 1 and a
    // rate that is not positive are refused
    @Test
    void aLimiter_needsABurstAndARate() {
        assertThatThrownBy(() -> new RateLimiter(0, 1, clock)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("burst");
        assertThatThrownBy(() -> new RateLimiter(-1, 1, clock)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("burst");
        assertThatThrownBy(() -> new RateLimiter(1, 0, clock)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("perSecond");
        assertThatThrownBy(() -> new RateLimiter(1, -1, clock)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("perSecond");
        assertThatThrownBy(() -> new RateLimiter(1, Double.NaN, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RateLimiter(1, Double.MIN_VALUE, clock).tryAcquire()).isTrue();
    }

    private static CallOutcome<CheckoutView> tooMany(Duration retryAfter) {
        return new CallOutcome.ProtocolError<>(429, "busy", "slow down", Optional.of(retryAfter));
    }

    // Covers: 8.3. Kills RateLimiter.record:79 RemoveConditional_EQUAL_IF: a shorter Retry-After never shortens a block that a
    // longer one set
    @Test
    void aShorterRetryAfter_doesNotShortenTheBlock() {
        RateLimiter limiter = new RateLimiter(10, 5, clock);
        limiter.record(tooMany(Duration.ofSeconds(10)));
        limiter.record(tooMany(Duration.ofSeconds(2)));

        clock.advance(Duration.ofSeconds(5));
        assertThat(limiter.blocked()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(5));
        assertThat(limiter.blocked()).isFalse();
        assertThat(limiter.tryAcquire()).isTrue();
    }

    // Covers: 8.3. A Retry-After longer than 30 s blocks for 30 s; a 429 without one, and any other outcome, blocks nothing
    @Test
    void theBlock_isCappedAtThirtySeconds_andOnlyA429WithARetryAfterBlocks() {
        RateLimiter limiter = new RateLimiter(10, 5, clock);
        limiter.record(tooMany(Duration.ofHours(1)));
        clock.advance(RateLimiter.MAX_BLOCK.minusMillis(1));
        assertThat(limiter.blocked()).isTrue();
        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.blocked()).isFalse();

        RateLimiter other = new RateLimiter(10, 5, clock);
        other.record(new CallOutcome.ProtocolError<>(429, "busy", "x", Optional.empty()));
        other.record(new CallOutcome.ProtocolError<>(503, "busy", "x", Optional.of(Duration.ofSeconds(9))));
        other.record(new CallOutcome.NotSent<>("x"));
        assertThat(other.blocked()).isFalse();
    }

    // Covers: 7.8, 8.3. Kills RateLimiter.available:100, blocked:110 and record:83 VoidMethodCall (a lock left held): another
    // thread still gets through after each of them
    @Test
    void theLimitersLock_isReleasedByEveryMethod() {
        RateLimiter limiter = new RateLimiter(10, 5, clock);
        limiter.available();
        assertThat(onAnotherThread(limiter::tryAcquire)).isTrue();
        limiter.blocked();
        assertThat(onAnotherThread(limiter::tryAcquire)).isTrue();
        limiter.record(tooMany(Duration.ofSeconds(1)));
        assertThat(onAnotherThread(limiter::blocked)).isTrue();
    }

    // Covers: 8.3. Kills RateLimiter.refill:116 RemoveConditional_EQUAL_IF: a clock that steps back does not take tokens away
    @Test
    void aClockThatStepsBack_doesNotConsumeTokens() {
        RateLimiter limiter = new RateLimiter(10, 5, clock);
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.tryAcquire()).isTrue();

        clock.advance(Duration.ofSeconds(-5));

        assertThat(limiter.available()).isEqualTo(9);
        for (int i = 0; i < 9; i++) {
            assertThat(limiter.tryAcquire()).as("token " + i).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();
    }

    // ---------------------------------------------------------------- the joined gate

    /** A gate that refuses while {@code refusing}, with the reason it names, and records what it is told. */
    private static final class Stub implements Gate {
        final String name;
        boolean refusing;
        final List<String> told = new ArrayList<>();

        Stub(String name) {
            this.name = name;
        }

        @Override
        public boolean tryAcquire() {
            told.add("acquire");
            return !refusing;
        }

        @Override
        public String reason() {
            return name;
        }

        @Override
        public void record(CallOutcome<?> outcome) {
            told.add("record");
        }

        @Override
        public void recordReplied() {
            told.add("replied");
        }
    }

    // Covers: 8.3. Kills Gates$1.tryAcquire:31 VoidMethodCall (ThreadLocal.remove), Gates$1.reason:38 RemoveConditional_EQUAL_ELSE and
    // recordReplied:48: the reason of the last refusal is the refusing gate's, "none" when nothing was refused or the last attempt
    // was admitted; gates are asked in order up to the first refusal; every gate hears of every outcome and of every reply
    @Test
    void theJoinedGate_namesTheGateThatRefused_andTellsEveryGate() {
        Stub first = new Stub("rate_limited");
        Stub second = new Stub("circuit_open");
        Gate all = Gates.all(first, second);

        assertThat(all.reason()).as("nothing refused yet").isEqualTo("none");
        assertThat(all.tryAcquire()).isTrue();
        assertThat(all.reason()).isEqualTo("none");

        second.refusing = true;
        assertThat(all.tryAcquire()).isFalse();
        assertThat(all.reason()).isEqualTo("circuit_open");
        first.refusing = true;
        first.told.clear();
        second.told.clear();
        assertThat(all.tryAcquire()).isFalse();
        assertThat(all.reason()).isEqualTo("rate_limited");
        assertThat(second.told).as("the second gate is not asked after the first refused").isEmpty();

        first.refusing = false;
        second.refusing = false;
        assertThat(all.tryAcquire()).isTrue();
        assertThat(all.reason()).as("an admitted attempt clears the reason").isEqualTo("none");

        first.told.clear();
        second.told.clear();
        all.record(failure());
        all.recordReplied();
        assertThat(first.told).containsExactly("record", "replied");
        assertThat(second.told).containsExactly("record", "replied");
    }

    // Covers: 8.3. Kills Gate$1.reason:20 EmptyObjectReturnVals (NO_COVERAGE): the gate that admits everything says "none"
    @Test
    void theGateThatAdmitsEverything_admitsAndIgnores() {
        assertThat(Gate.NONE.tryAcquire()).isTrue();
        assertThat(Gate.NONE.reason()).isEqualTo("none");
        Gate.NONE.record(failure());
        Gate.NONE.recordReplied();
        assertThat(Gate.NONE.tryAcquire()).isTrue();
    }
}
