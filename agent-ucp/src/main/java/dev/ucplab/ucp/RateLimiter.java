package dev.ucplab.ucp;

import dev.ucplab.core.merchant.CallOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A token bucket for one merchant (8.3, 19): {@code burst} tokens, refilled at {@code perSecond}, on the injected
 * {@link Clock}. {@link #tryAcquire} takes a token or says no; a refusal reaches the executor as a call that was not sent,
 * and nothing reaches the merchant. A 429 with {@code Retry-After: n} blocks the bucket until now plus {@code n} seconds
 * (at most {@link #MAX_BLOCK}) for every caller, not only the one that was told (19: it honours 429 and Retry-After).
 * Calls that bypass the gate, the reconciliation reads (7.5), never reach it.
 *
 * <p>The lock guards a few fields of in-memory state and is never held across a call (7.8).
 */
public final class RateLimiter implements Gate {

    /** The longest a {@code Retry-After} blocks the bucket. */
    public static final Duration MAX_BLOCK = Duration.ofSeconds(30);

    private final double burst;
    private final double perSecond;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private double tokens;
    private Instant refilledAt;
    private Instant blockedUntil;

    public RateLimiter(int burst, double perSecond, Clock clock) {
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be at least 1: " + burst);
        }
        if (!(perSecond > 0)) {
            throw new IllegalArgumentException("perSecond must be positive: " + perSecond);
        }
        this.burst = burst;
        this.perSecond = perSecond;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokens = burst;
        this.refilledAt = clock.instant();
        this.blockedUntil = refilledAt;
    }

    @Override
    public boolean tryAcquire() {
        lock.lock();
        try {
            Instant now = clock.instant();
            if (now.isBefore(blockedUntil)) {
                return false;
            }
            refill(now);
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String reason() {
        return "rate_limited";
    }

    @Override
    public void record(CallOutcome<?> outcome) {
        if (outcome instanceof CallOutcome.ProtocolError<?> e && e.httpStatus() == 429 && e.retryAfter().isPresent()) {
            Duration wait = e.retryAfter().get();
            Duration capped = wait.compareTo(MAX_BLOCK) > 0 ? MAX_BLOCK : wait;
            lock.lock();
            try {
                Instant until = clock.instant().plus(capped);
                if (until.isAfter(blockedUntil)) {
                    blockedUntil = until;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    @Override
    public void recordReplied() {
        // A usable reply that is not a checkout (a profile) says nothing about the rate.
    }

    /** Tokens now, for tests and diagnostics. */
    public double available() {
        lock.lock();
        try {
            refill(clock.instant());
            return tokens;
        } finally {
            lock.unlock();
        }
    }

    /** Whether a 429 is blocking the bucket now. */
    public boolean blocked() {
        lock.lock();
        try {
            return clock.instant().isBefore(blockedUntil);
        } finally {
            lock.unlock();
        }
    }

    /** Caller holds the lock. */
    private void refill(Instant now) {
        if (now.isAfter(refilledAt)) {
            double seconds = Duration.between(refilledAt, now).toNanos() / 1e9;
            tokens = Math.min(burst, tokens + seconds * perSecond);
            refilledAt = now;
        }
    }
}
