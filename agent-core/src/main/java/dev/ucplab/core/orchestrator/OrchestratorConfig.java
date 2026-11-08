package dev.ucplab.core.orchestrator;

import java.time.Duration;
import java.util.Objects;

/**
 * Orchestrator settings (7.5, 7.6, 8.2, 8.3). Built once at startup; an invalid combination fails startup.
 *
 * @param lease the worker lease (7.6)
 * @param completeRequestTimeout the {@code complete} request timeout (8.3), the longest request timeout
 * @param resendQuietPeriod minimum time between a {@code complete} attempt and its resend (7.5)
 * @param reconcileDeadline time from the first uncertainty to ESCALATED (7.5)
 * @param maxCompleteResends resends of {@code complete} under one key (7.5)
 * @param maxQuoteRetries retries of a create or update under one key before FAILED_NOT_CHARGED (7.2, 8.2)
 */
// pollInitial, pollMax and jitter (7.5) pace the calls to advance, so they belong to RecoveryWorker (M3), not here.
public record OrchestratorConfig(
        Duration lease,
        Duration completeRequestTimeout,
        Duration resendQuietPeriod,
        Duration reconcileDeadline,
        int maxCompleteResends,
        int maxQuoteRetries) {

    public OrchestratorConfig {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(completeRequestTimeout, "completeRequestTimeout");
        Objects.requireNonNull(resendQuietPeriod, "resendQuietPeriod");
        Objects.requireNonNull(reconcileDeadline, "reconcileDeadline");
        if (resendQuietPeriod.compareTo(completeRequestTimeout) < 0) {
            throw new IllegalArgumentException("7.5: resendQuietPeriod " + resendQuietPeriod
                    + " is shorter than the complete request timeout " + completeRequestTimeout);
        }
        Duration minLease = completeRequestTimeout.multipliedBy(2).plusSeconds(5);
        if (lease.compareTo(minLease) < 0) {
            throw new IllegalArgumentException("7.6: lease " + lease + " is below 2 x the longest request timeout"
                    + " + 5 s (" + minLease + ")");
        }
        if (!reconcileDeadline.isPositive()) {
            throw new IllegalArgumentException("reconcileDeadline must be positive: " + reconcileDeadline);
        }
        if (maxCompleteResends < 0 || maxQuoteRetries < 0) {
            throw new IllegalArgumentException("retry counts must not be negative");
        }
    }

    /** Lease 30 s, complete timeout 10 s, quiet period 10 s, deadline 60 s, 3 resends, 3 retries. */
    public static OrchestratorConfig defaults() {
        return new OrchestratorConfig(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofSeconds(60), 3, 3);
    }
}
