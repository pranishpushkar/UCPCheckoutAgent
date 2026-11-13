package dev.ucplab.core.id;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UuidV7IdGeneratorTest {

    private static final Instant T0 = Instant.parse("2026-11-12T10:00:00.123Z");
    private static final Pattern CANONICAL =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private final MutableClock clock = new MutableClock(T0);

    private static long millisOf(String id) {
        return UUID.fromString(id).getMostSignificantBits() >>> 16;
    }

    // Covers: Appendix B (UUIDv7 in production), 7.8 (the timestamp comes from the injected clock): canonical lowercase
    // form, version 7, RFC 9562 variant, and the top 48 bits are the clock's Unix milliseconds
    @Test
    void next_isACanonicalUuidV7_stampedWithTheInjectedClock() {
        String id = new UuidV7IdGenerator(clock, new SplittableRandom(1)).next();

        assertThat(id).matches(CANONICAL);
        UUID uuid = UUID.fromString(id);
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(millisOf(id)).isEqualTo(T0.toEpochMilli());
    }

    // Covers: 7.4, 7.6 (ids within one millisecond are distinct and strictly increasing as strings)
    @Test
    void next_isStrictlyIncreasing_withinOneMillisecond() {
        IdGenerator ids = new UuidV7IdGenerator(clock, new SplittableRandom(2));
        String previous = ids.next();
        for (int i = 0; i < 100_000; i++) {
            String id = ids.next();
            assertThat(id).isGreaterThan(previous);
            previous = id;
        }
        assertThat(millisOf(previous)).isEqualTo(T0.toEpochMilli());
    }

    // Covers: 7.4, 7.8 (a clock that goes back never makes an id go back: the last millisecond is kept)
    @Test
    void next_staysIncreasing_whenTheClockGoesBack() {
        IdGenerator ids = new UuidV7IdGenerator(clock, new SplittableRandom(3));
        String before = ids.next();
        clock.advance(Duration.ofSeconds(-1));

        String after = ids.next();

        assertThat(after).isGreaterThan(before);
        assertThat(millisOf(after)).isEqualTo(T0.toEpochMilli());
    }

    // Covers: 7.4 (when the 74 random bits are exhausted within one millisecond, the next id moves to the next one)
    @Test
    void next_movesToTheNextMillisecond_whenTheCounterOverflows() {
        RandomGenerator allOnes = () -> -1L;
        IdGenerator ids = new UuidV7IdGenerator(clock, allOnes);
        String first = ids.next();

        String second = ids.next();

        assertThat(second).isGreaterThan(first).matches(CANONICAL);
        assertThat(millisOf(second)).isEqualTo(T0.toEpochMilli() + 1);
    }

    // Covers: 7.4, 7.6 (two generators, as two processes would have, share no id)
    @Test
    void twoInstances_withTheSameClock_shareNoId() {
        IdGenerator a = new UuidV7IdGenerator(clock, new SplittableRandom(4));
        IdGenerator b = new UuidV7IdGenerator(clock, new SplittableRandom(5));
        Set<String> fromA = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            fromA.add(a.next());
        }
        for (int i = 0; i < 100_000; i++) {
            assertThat(fromA).doesNotContain(b.next());
        }
        assertThat(fromA).hasSize(100_000);
    }

    // Covers: 7.8 (no lock: concurrent callers of one instance still get distinct ids)
    @Test
    void next_isDistinct_underConcurrentCallers() throws Exception {
        IdGenerator ids = new UuidV7IdGenerator(clock, new SplittableRandom(6));
        Set<String> seen = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 10_000; i++) {
                        seen.add(ids.next());
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(seen).hasSize(80_000);
    }
}
