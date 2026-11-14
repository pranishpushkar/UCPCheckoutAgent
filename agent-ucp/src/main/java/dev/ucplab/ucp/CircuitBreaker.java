package dev.ucplab.ucp;

import dev.ucplab.core.merchant.CallOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The circuit breaker of 8.3 for one host:port, in memory, driven by the injected {@link Clock} (ADR 0001).
 *
 * <p>It opens after {@code breakerThreshold} consecutive failures (default 5) and stays open for {@code breakerOpenFor}
 * (30 s). Then it admits exactly one probe; a successful probe closes it, a failed one re-opens it for another full
 * period. A probe whose result never arrives is replaced after one more period, so a crashed caller cannot block a
 * host for good.
 *
 * <p>A failure is a {@code NotSent}, an {@code Uncertain} or a {@code ProtocolError} with a 5xx status (503 included).
 * An {@code Ok}, a {@code Rejected}, a 4xx and a 429 are not failures: the host answered, so they reset the count. A
 * call the breaker itself rejected is never recorded. A result that arrives while the breaker is open is ignored. The
 * lock guards only these few fields and is never held across a call (7.8).
 */
public final class CircuitBreaker implements Gate {

    /** The breaker's state, for tests and metrics. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int threshold;
    private final Duration openFor;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant since = Instant.MIN;

    public CircuitBreaker(UcpConfig config, Clock clock) {
        Objects.requireNonNull(config, "config");
        this.threshold = config.breakerThreshold();
        this.openFor = config.breakerOpenFor();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean tryAcquire() {
        lock.lock();
        try {
            Instant now = clock.instant();
            switch (state) {
                case CLOSED:
                    return true;
                case OPEN:
                case HALF_OPEN:
                    // OPEN since `since`, or the probe went out at `since`: after one period a probe is (again) due.
                    if (!now.isBefore(since.plus(openFor))) {
                        state = State.HALF_OPEN;
                        since = now;
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void record(CallOutcome<?> outcome) {
        Objects.requireNonNull(outcome, "outcome");
        record(isFailure(outcome));
    }

    @Override
    public void recordReplied() {
        record(false);
    }

    private void record(boolean failure) {
        lock.lock();
        try {
            switch (state) {
                case CLOSED -> {
                    consecutiveFailures = failure ? consecutiveFailures + 1 : 0;
                    if (consecutiveFailures >= threshold) {
                        open();
                    }
                }
                case HALF_OPEN -> {
                    if (failure) {
                        open();
                    } else {
                        state = State.CLOSED;
                        consecutiveFailures = 0;
                    }
                }
                case OPEN -> {
                    // A late result from before the breaker opened: it says nothing about the host now.
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String reason() {
        return "circuit_open";
    }

    public State state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    private void open() {
        state = State.OPEN;
        since = clock.instant();
    }

    private static boolean isFailure(CallOutcome<?> outcome) {
        return switch (outcome) {
            case CallOutcome.NotSent<?> n -> true;
            case CallOutcome.Uncertain<?> u -> true;
            case CallOutcome.ProtocolError<?> e -> e.httpStatus() >= 500;
            case CallOutcome.Ok<?> ok -> false;
            case CallOutcome.Rejected<?> r -> false;
        };
    }
}
