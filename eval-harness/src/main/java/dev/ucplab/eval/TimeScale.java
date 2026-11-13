package dev.ucplab.eval;

import dev.ucplab.core.orchestrator.OrchestratorConfig;
import dev.ucplab.core.recovery.RecoveryConfig;
import dev.ucplab.ucp.UcpConfig;
import java.time.Duration;
import java.util.List;

/**
 * One factor k that divides every duration of a scripted run (13.1, M4 note). The harness runs on real time, so k buys
 * speed at the cost of headroom; it is refused unless every ordering the design depends on still holds, and every race
 * a scenario relies on keeps at least {@link #MIN_MARGIN} of slack. The lease cannot scale linearly because of the fixed
 * 5 s in 7.6 ({@code L >= 2 Tc + 5 s}), which is what caps k at 5 (the S9 ordering breaks at 6).
 *
 * <p>Approval TTL and maximum sign-in age are not scaled: no M4 scenario reaches them.
 */
public record TimeScale(int k) {

    /** The smallest slack accepted on a race between real-time events. */
    public static final Duration MIN_MARGIN = Duration.ofSeconds(1);

    private static final Duration FIXED_LEASE_TERM = Duration.ofSeconds(5);

    /** One ordering: {@code smaller} must stay below {@code larger} by at least {@code minMargin} (zero: not above). */
    public record Ordering(String name, Duration smaller, Duration larger, Duration minMargin) {
        public Duration margin() {
            return larger.minus(smaller);
        }

        public boolean holds() {
            return margin().compareTo(minMargin) >= 0;
        }
    }

    /** @throws IllegalArgumentException if k is below 1 or any ordering fails; the message names each one */
    public TimeScale {
        if (k < 1) {
            throw new IllegalArgumentException("time scale k must be at least 1: " + k);
        }
        List<String> broken = orderings(k).stream().filter(o -> !o.holds())
                .map(o -> o.name() + " (" + o.smaller() + " vs " + o.larger() + ", margin " + o.margin()
                        + ", needs " + o.minMargin() + ")")
                .toList();
        if (!broken.isEmpty()) {
            throw new IllegalArgumentException("time scale k = " + k + " breaks: " + String.join("; ", broken));
        }
    }

    public static TimeScale of(int k) {
        return new TimeScale(k);
    }

    private static Duration div(Duration d, int k) {
        return d.dividedBy(k);
    }

    private static Duration completeTimeout(int k) {
        return div(Duration.ofSeconds(10), k);
    }

    private static Duration lease(int k) {
        Duration floor = completeTimeout(k).multipliedBy(2).plus(FIXED_LEASE_TERM);
        Duration scaled = div(Duration.ofSeconds(30), k);
        return scaled.compareTo(floor) >= 0 ? scaled : floor;
    }

    public Duration completeTimeout() {
        return completeTimeout(k);
    }

    /** {@code L}: 30 s / k, but never below {@code 2 Tc + 5 s} (7.6). */
    public Duration lease() {
        return lease(k);
    }

    /** {@code P}: 5 s / k. */
    public Duration workerPeriod() {
        return div(Duration.ofSeconds(5), k);
    }

    /** S3: the complete answer is held 1.5 Tc, past the client's timeout. */
    public Duration s3Hold() {
        return completeTimeout().multipliedBy(3).dividedBy(2);
    }

    /** S8: the merchant answers 503 for 20 s / k. */
    public Duration s8Outage() {
        return div(Duration.ofSeconds(20), k);
    }

    /** S17 (not in M4): a hold of 1.2 Tc, longer than the timeout and shorter than the quiet period. */
    public Duration s17Hold() {
        return completeTimeout().multipliedBy(6).dividedBy(5);
    }

    public UcpConfig ucp(String ucpAgent) {
        UcpConfig d = UcpConfig.defaults(ucpAgent);
        return new UcpConfig(ucpAgent, div(d.connectTimeout(), k), div(d.requestTimeout(), k),
                div(d.completeTimeout(), k), d.maxRetries(), div(d.backoffInitial(), k), div(d.backoffCap(), k),
                d.breakerThreshold(), div(d.breakerOpenFor(), k), d.maxResponseBytes());
    }

    public OrchestratorConfig orchestrator() {
        OrchestratorConfig d = OrchestratorConfig.defaults();
        return new OrchestratorConfig(lease(), div(d.completeRequestTimeout(), k), div(d.resendQuietPeriod(), k),
                div(d.reconcileDeadline(), k), d.maxCompleteResends(), div(d.pollInitial(), k), div(d.pollMax(), k),
                d.pollJitter());
    }

    public RecoveryConfig recovery() {
        RecoveryConfig d = RecoveryConfig.defaults();
        return new RecoveryConfig(workerPeriod(), lease(), d.limit(), d.maxStepsPerIntent());
    }

    public List<Ordering> orderings() {
        return orderings(k);
    }

    /** The orderings of m4-plan section 3, each named after the rule or scenario that needs it. */
    static List<Ordering> orderings(int k) {
        OrchestratorConfig d = OrchestratorConfig.defaults();
        Duration tc = completeTimeout(k);
        Duration quiet = div(d.resendQuietPeriod(), k);
        Duration deadline = div(d.reconcileDeadline(), k);
        Duration pollMax = div(d.pollMax(), k);
        Duration lease = lease(k);
        Duration period = div(Duration.ofSeconds(5), k);
        Duration s3Hold = tc.multipliedBy(3).dividedBy(2);
        Duration s8Outage = div(Duration.ofSeconds(20), k);
        Duration s17Hold = tc.multipliedBy(6).dividedBy(5);
        Duration halfLease = lease.dividedBy(2);
        Duration none = Duration.ZERO;
        return List.of(
                new Ordering("7.5 quiet period >= complete timeout", tc, quiet, none),
                new Ordering("7.6 lease >= 2 x complete timeout + 5 s",
                        tc.multipliedBy(2).plus(FIXED_LEASE_TERM), lease, none),
                new Ordering("7.5 deadline > resends x quiet period", quiet.multipliedBy(d.maxCompleteResends()),
                        deadline, Duration.ofNanos(1)),
                new Ordering("S3 response hold > complete timeout", tc, s3Hold, MIN_MARGIN),
                new Ordering("S8 outage < reconcile deadline", s8Outage, deadline, MIN_MARGIN),
                new Ordering("S9 takeover (lease + period) inside the reconcile deadline", lease.plus(period), deadline,
                        MIN_MARGIN),
                new Ordering("7.6 worker period <= lease / 2", period, halfLease, none),
                new Ordering("7.6 poll max <= lease / 2", pollMax, halfLease, none),
                // The late kill comes at D - L/2 after uncertain_since; the last renewal was at most pollMax earlier,
                // so the lease runs out no sooner than D - L/2 - pollMax + L: past the deadline by L/2 - pollMax.
                new Ordering("S28 takeover after the reconcile deadline", deadline,
                        deadline.minus(halfLease).minus(pollMax).plus(lease), MIN_MARGIN),
                new Ordering("S17 complete timeout < hold", tc, s17Hold, Duration.ofNanos(1)),
                new Ordering("S17 hold < quiet period", s17Hold, quiet, Duration.ofNanos(1)));
    }
}
