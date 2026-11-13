package dev.ucplab.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock the test moves by hand; safe to read from the service's threads. */
final class FakeClock extends Clock {

    private final AtomicReference<Instant> now;

    FakeClock(Instant start) {
        now = new AtomicReference<>(start);
    }

    void advance(Duration d) {
        now.updateAndGet(t -> t.plus(d));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }
}
