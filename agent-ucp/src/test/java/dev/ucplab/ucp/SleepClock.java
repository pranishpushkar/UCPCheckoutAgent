package dev.ucplab.ucp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock that a recording sleeper moves: a test that records the sleeps the executor asks for, instead of sleeping, still
 * sees time pass by exactly that much, so a merchant's rate limiter that a 429 blocked until a time has let it go by the
 * time the executor retries (as a real sleep would).
 */
final class SleepClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-12T10:00:00Z"));

    void advance(Duration d) {
        now.updateAndGet(t -> t.plus(d));
    }

    /** A sleeper that records {@code d} in {@code into} and moves this clock by it. */
    Sleeper recordingInto(List<Duration> into) {
        return d -> {
            into.add(d);
            advance(d);
        };
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
