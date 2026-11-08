package dev.ucplab.core.id;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic {@link IdGenerator} for tests and the in-memory harness: {@code prefix-1}, {@code prefix-2}, and so on. */
public final class SequentialIdGenerator implements IdGenerator {

    private final String prefix;
    private final AtomicLong counter = new AtomicLong();

    public SequentialIdGenerator(String prefix) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    @Override
    public String next() {
        return prefix + '-' + counter.incrementAndGet();
    }
}
