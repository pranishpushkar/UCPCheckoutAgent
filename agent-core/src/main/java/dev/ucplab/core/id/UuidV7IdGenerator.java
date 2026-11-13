package dev.ucplab.core.id;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

/**
 * {@link IdGenerator} that issues UUIDv7 (RFC 9562, section 5.7), the production generator of Appendix B: 48 bits of
 * Unix milliseconds from the injected {@link Clock}, version {@code 0111}, 12 bits {@code rand_a}, variant {@code 10}
 * and 62 bits {@code rand_b}, written as lowercase canonical 8-4-4-4-12 hex, so string order is generation order.
 *
 * <p><b>Why it matters.</b> Intent ids feed the idempotency keys (7.4), so a repeated id would repeat a key; worker ids
 * must be unique per process start (7.6). A process restarted after a crash must therefore never reissue an id, which
 * a sequential generator would.
 *
 * <p><b>Monotonic within one instance</b> (RFC 9562 method 3, monotonic random). When the clock shows the same
 * millisecond as the last id, or an earlier one, the last millisecond is kept and the 74 random bits are incremented as
 * a counter; if that overflows, the id moves to the next millisecond. The state is swapped with compare-and-set, with no
 * lock (7.8), so uniqueness does not depend on the random source being thread-safe; production passes a
 * {@code SecureRandom}, which is.
 *
 * <p>For production and the eval child process only. Tests keep the seeded {@link SequentialIdGenerator}. The clock and
 * the random source are injected (7.8); core never calls {@code UUID.randomUUID()}.
 */
public final class UuidV7IdGenerator implements IdGenerator {

    private static final long RAND_A_MASK = (1L << 12) - 1;
    private static final long RAND_B_MASK = (1L << 62) - 1;

    /** The last id issued: milliseconds, then the 12-bit and 62-bit random fields. */
    private record State(long millis, long randA, long randB) {}

    private final Clock clock;
    private final RandomGenerator random;
    private final AtomicReference<State> last = new AtomicReference<>(new State(Long.MIN_VALUE, 0, 0));

    public UuidV7IdGenerator(Clock clock, RandomGenerator random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public String next() {
        while (true) {
            State prev = last.get();
            long now = clock.millis();
            State next = now > prev.millis() ? fresh(now) : increment(prev);
            if (last.compareAndSet(prev, next)) {
                long msb = (next.millis() << 16) | 0x7000L | next.randA();
                long lsb = Long.MIN_VALUE | next.randB();
                return new UUID(msb, lsb).toString();
            }
        }
    }

    private State fresh(long millis) {
        return new State(millis, random.nextLong() & RAND_A_MASK, random.nextLong() & RAND_B_MASK);
    }

    private State increment(State prev) {
        if (prev.randB() < RAND_B_MASK) {
            return new State(prev.millis(), prev.randA(), prev.randB() + 1);
        }
        if (prev.randA() < RAND_A_MASK) {
            return new State(prev.millis(), prev.randA() + 1, 0);
        }
        return fresh(prev.millis() + 1);
    }
}
