package dev.ucplab.ucp;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of the UCP client (sections 8.2 to 8.4).
 *
 * @param ucpAgent the {@code UCP-Agent} header value, for example {@code profile="https://agent.example/.well-known/ucp"}
 * @param connectTimeout connect timeout (8.3)
 * @param requestTimeout request timeout for every call except {@code complete} (8.3)
 * @param completeTimeout request timeout for {@code complete} (8.3)
 * @param maxRetries retries after the first attempt (8.2: "up to 3" is 3 retries, so 4 attempts)
 * @param backoffInitial first backoff delay (8.2)
 * @param backoffCap backoff cap, and the longest {@code Retry-After} the gateway sleeps through (8.2)
 * @param breakerThreshold consecutive failures that open the circuit breaker (8.3)
 * @param breakerOpenFor how long the breaker stays open before one probe (8.3)
 * @param maxResponseBytes the largest response body read; a larger one is an I/O failure, hence {@code Uncertain}
 * @param rateBurst the rate limiter's bucket size, per merchant (8.3)
 * @param ratePerSecond the rate limiter's refill rate, tokens a second, per merchant (8.3)
 */
public record UcpConfig(
        String ucpAgent,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration completeTimeout,
        int maxRetries,
        Duration backoffInitial,
        Duration backoffCap,
        int breakerThreshold,
        Duration breakerOpenFor,
        int maxResponseBytes,
        int rateBurst,
        double ratePerSecond) {

    /** The limits of 8.3: a burst of 10 and 5 a second. */
    public static final int DEFAULT_RATE_BURST = 10;
    public static final double DEFAULT_RATE_PER_SECOND = 5;

    /** The settings before the rate limiter existed, with its defaults: every call site of M2 to M6 keeps compiling. */
    public UcpConfig(
            String ucpAgent, Duration connectTimeout, Duration requestTimeout, Duration completeTimeout, int maxRetries,
            Duration backoffInitial, Duration backoffCap, int breakerThreshold, Duration breakerOpenFor,
            int maxResponseBytes) {
        this(ucpAgent, connectTimeout, requestTimeout, completeTimeout, maxRetries, backoffInitial, backoffCap,
                breakerThreshold, breakerOpenFor, maxResponseBytes, DEFAULT_RATE_BURST, DEFAULT_RATE_PER_SECOND);
    }

    public UcpConfig {
        Objects.requireNonNull(ucpAgent, "ucpAgent");
        if (ucpAgent.isBlank()) {
            throw new IllegalArgumentException("ucpAgent must not be blank");
        }
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(requestTimeout, "requestTimeout");
        requirePositive(completeTimeout, "completeTimeout");
        requirePositive(backoffInitial, "backoffInitial");
        requirePositive(backoffCap, "backoffCap");
        requirePositive(breakerOpenFor, "breakerOpenFor");
        if (completeTimeout.compareTo(requestTimeout) < 0) {
            throw new IllegalArgumentException("completeTimeout must not be shorter than requestTimeout");
        }
        if (backoffCap.compareTo(backoffInitial) < 0) {
            throw new IllegalArgumentException("backoffCap must not be shorter than backoffInitial");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative: " + maxRetries);
        }
        if (breakerThreshold < 1) {
            throw new IllegalArgumentException("breakerThreshold must be at least 1: " + breakerThreshold);
        }
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("maxResponseBytes must be at least 1: " + maxResponseBytes);
        }
        if (rateBurst < 1) {
            throw new IllegalArgumentException("rateBurst must be at least 1: " + rateBurst);
        }
        if (!(ratePerSecond > 0)) {
            throw new IllegalArgumentException("ratePerSecond must be positive: " + ratePerSecond);
        }
    }

    /** These settings with another rate limit. */
    public UcpConfig withRate(int burst, double perSecond) {
        return new UcpConfig(ucpAgent, connectTimeout, requestTimeout, completeTimeout, maxRetries, backoffInitial,
                backoffCap, breakerThreshold, breakerOpenFor, maxResponseBytes, burst, perSecond);
    }

    /** The defaults of sections 8.2 and 8.3: 2 s connect, 5 s request, 10 s complete, 3 retries, 5 failures, 30 s. */
    public static UcpConfig defaults(String ucpAgent) {
        return new UcpConfig(ucpAgent, Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10), 3,
                Duration.ofMillis(200), Duration.ofSeconds(2), 5, Duration.ofSeconds(30), 1 << 20,
                DEFAULT_RATE_BURST, DEFAULT_RATE_PER_SECOND);
    }

    private static void requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive: " + d);
        }
    }
}
