package dev.ucplab.faultproxy;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * The proxy's request log (§12 Admin API). {@code seq} is taken on arrival from one counter, so concurrent requests get
 * distinct, contiguous numbers. Entries are immutable and replaced as a request moves on. The newest
 * {@link #CAPACITY} entries are kept. Lock-free: a concurrent sorted map and an atomic counter.
 */
final class RequestLog {

    static final int CAPACITY = 100_000;

    private final Clock clock;
    private final AtomicLong seq = new AtomicLong();
    private final ConcurrentSkipListMap<Long, LogEntry> entries = new ConcurrentSkipListMap<>();

    RequestLog(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    LogEntry open(String method, String path, Optional<String> requestId, Optional<String> idempotencyKey,
                  Optional<String> bodyHmac, Optional<String> action, Optional<String> ruleId) {
        long n = seq.incrementAndGet();
        LogEntry entry = new LogEntry(n, clock.instant(), method, path, requestId, idempotencyKey, bodyHmac, 0,
                OptionalInt.empty(), action, ruleId, false, false);
        entries.put(n, entry);
        entries.remove(n - CAPACITY);
        return entry;
    }

    /** Replaces the entry with {@code change} applied; an entry already dropped for capacity stays dropped. */
    void update(long n, UnaryOperator<LogEntry> change) {
        entries.computeIfPresent(n, (k, e) -> change.apply(e));
    }

    /** Entries with {@code seq > after}, in {@code seq} order. */
    List<LogEntry> after(long after) {
        return List.copyOf(entries.tailMap(after, false).values());
    }
}
