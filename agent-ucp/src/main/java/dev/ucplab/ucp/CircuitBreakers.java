package dev.ucplab.ucp;

import dev.ucplab.core.merchant.MerchantId;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The admission state of each merchant (8.3, 19): one {@link CircuitBreaker} and one {@link RateLimiter} per
 * {@link MerchantId}, shared by that merchant's checkout and catalog calls. A merchant may use more than one host (8.5),
 * so the host and port are not the key. Share one instance between the gateway and the catalog client of every merchant:
 * a second instance would give a second, separate breaker for the same merchant.
 */
public final class CircuitBreakers {

    private final UcpConfig config;
    private final Clock clock;
    private final ConcurrentHashMap<MerchantId, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MerchantId, RateLimiter> limiters = new ConcurrentHashMap<>();

    public CircuitBreakers(UcpConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The breaker of {@code merchant}; created on first use. */
    public CircuitBreaker forMerchant(MerchantId merchant) {
        Objects.requireNonNull(merchant, "merchant");
        return breakers.computeIfAbsent(merchant, k -> new CircuitBreaker(config, clock));
    }

    /** The rate limiter of {@code merchant}; created on first use. */
    public RateLimiter limiterFor(MerchantId merchant) {
        Objects.requireNonNull(merchant, "merchant");
        return limiters.computeIfAbsent(merchant, k -> new RateLimiter(config.rateBurst(), config.ratePerSecond(), clock));
    }

    /** What {@link RetryExecutor} asks before each attempt: the merchant's limiter, then its breaker. */
    public Gate gateFor(MerchantId merchant) {
        return Gates.all(limiterFor(merchant), forMerchant(merchant));
    }
}
