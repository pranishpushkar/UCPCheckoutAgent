package dev.ucplab.core.recovery;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of {@link RecoveryWorker} and {@link RecoveryLoop} (7.6).
 *
 * @param period how often the loop runs a pass (5 s); at most half the lease (7.6 lease rules)
 * @param lease the lease a claim takes and each renewal extends (30 s); the same value the orchestrator uses
 * @param limit the most intents one pass claims
 * @param maxStepsPerIntent a guard on one intent's series of {@code advance} calls within a pass
 */
public record RecoveryConfig(Duration period, Duration lease, int limit, int maxStepsPerIntent) {

    public RecoveryConfig {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(lease, "lease");
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("period must be positive: " + period);
        }
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive: " + lease);
        }
        if (period.compareTo(lease.dividedBy(2)) > 0) {
            throw new IllegalArgumentException("7.6: worker period " + period + " is longer than half the lease "
                    + lease + " (" + lease.dividedBy(2) + ")");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
        if (maxStepsPerIntent < 1) {
            throw new IllegalArgumentException("maxStepsPerIntent must be at least 1: " + maxStepsPerIntent);
        }
    }

    /** A pass every 5 s, the 30 s lease of {@code OrchestratorConfig.defaults()}, 50 intents, 500 steps each. */
    public static RecoveryConfig defaults() {
        return new RecoveryConfig(Duration.ofSeconds(5), Duration.ofSeconds(30), 50, 500);
    }
}
