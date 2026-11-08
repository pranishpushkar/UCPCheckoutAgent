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
 * @param pollInitial first reconciliation poll delay (7.5)
 * @param pollMax largest reconciliation poll delay (7.5)
 * @param pollJitter relative jitter on each poll delay, in [0, 1) (7.5)
 */
public record OrchestratorConfig(
        Duration lease,
        Duration completeRequestTimeout,
        Duration resendQuietPeriod,
        Duration reconcileDeadline,
        int maxCompleteResends,
        int maxQuoteRetries,
        Duration pollInitial,
        Duration pollMax,
        double pollJitter) {

    public OrchestratorConfig {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(completeRequestTimeout, "completeRequestTimeout");
        Objects.requireNonNull(resendQuietPeriod, "resendQuietPeriod");
        Objects.requireNonNull(reconcileDeadline, "reconcileDeadline");
        Objects.requireNonNull(pollInitial, "pollInitial");
        Objects.requireNonNull(pollMax, "pollMax");
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
        Duration resendSpan = resendQuietPeriod.multipliedBy(maxCompleteResends);
        if (reconcileDeadline.compareTo(resendSpan) <= 0) {
            throw new IllegalArgumentException("7.5: reconcileDeadline " + reconcileDeadline + " must be greater than "
                    + maxCompleteResends + " resends x quiet period " + resendQuietPeriod + " (" + resendSpan
                    + "), or the resend cap can never be reached");
        }
        if (!pollInitial.isPositive() || pollMax.compareTo(pollInitial) < 0) {
            throw new IllegalArgumentException("7.5: need 0 < pollInitial <= pollMax: " + pollInitial + ", " + pollMax);
        }
        if (!(pollJitter >= 0 && pollJitter < 1)) {
            throw new IllegalArgumentException("7.5: pollJitter must be in [0, 1): " + pollJitter);
        }
    }

    /**
     * Lease 30 s, complete timeout 10 s, quiet period 20 s (twice the complete timeout, 7.5), deadline 60 s,
     * 2 resends (complete at 0, 20 and 40 s; 7.5 "Reconciliation defaults"), 3 retries, polls from 250 ms to 2 s with
     * 20% jitter.
     */
    public static OrchestratorConfig defaults() {
        return new OrchestratorConfig(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(20),
                Duration.ofSeconds(60), 2, 3, Duration.ofMillis(250), Duration.ofSeconds(2), 0.2);
    }
}
