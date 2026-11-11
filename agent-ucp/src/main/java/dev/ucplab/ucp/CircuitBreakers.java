package dev.ucplab.ucp;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** One {@link CircuitBreaker} per {@code host:port} (8.3). In the lab every merchant is on localhost, so the port matters. */
public final class CircuitBreakers {

    private final UcpConfig config;
    private final Clock clock;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakers(UcpConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The breaker for {@code hostPort}, for example {@code 127.0.0.1:8081}; created on first use. */
    public CircuitBreaker forHostPort(String hostPort) {
        Objects.requireNonNull(hostPort, "hostPort");
        return breakers.computeIfAbsent(hostPort, k -> new CircuitBreaker(config, clock));
    }
}
