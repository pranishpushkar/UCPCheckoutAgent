package dev.ucplab.core.policy;

import dev.ucplab.core.intent.TransitionGuard;
import java.util.Objects;

/**
 * Rules P7 and P8 (9.1), applied by the orchestrator when a {@code complete} or a reconciliation
 * read reports price_changed or incomplete (table 7.2). Not part of AgentBudget: the model is not
 * involved in re-quoting.
 */
public final class RequotePolicy {

    /** {@code failure_reason} when the P8 cap is reached (table 7.2). */
    public static final String PRICE_UNSTABLE = "price_unstable";

    private final PolicyConfig config;

    public RequotePolicy(PolicyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * P8. With the default cap of 2, re-quotes at counts 0 and 1 are allowed and the next one is
     * denied, so an intent gets at most 3 approvals (S16).
     *
     * @param requoteCount the intent's {@code requote_count} before this re-quote
     */
    public PolicyDecision evaluateRequoteCap(int requoteCount) {
        if (requoteCount < 0) {
            throw new IllegalArgumentException("requoteCount must not be negative: " + requoteCount);
        }
        if (requoteCount >= config.maxRequotes()) {
            return PolicyDecision.deny(PolicyRule.P8, PRICE_UNSTABLE);
        }
        return PolicyDecision.allow("re-quote " + (requoteCount + 1) + " of " + config.maxRequotes());
    }

    /** The table 7.2 guard for a re-quote row, from P8. */
    public TransitionGuard requoteGuard(int requoteCount) {
        return evaluateRequoteCap(requoteCount).allowed()
                ? TransitionGuard.REQUOTE_BELOW_CAP
                : TransitionGuard.REQUOTE_CAP_REACHED;
    }

    /**
     * The outcome of a re-quote. P8 is checked first: at the cap the re-quote is
     * {@link RequoteOutcome.Rejected} with {@value #PRICE_UNSTABLE}. Below the cap, P7 applies: every
     * re-quote needs a new approval, whether the total went up or down, so the outcome is
     * {@link RequoteOutcome.ApprovalRequired}, never a rejection. P7 is always on and not configurable.
     *
     * @param requoteCount the intent's {@code requote_count} before this re-quote
     */
    public RequoteOutcome evaluateReapproval(int requoteCount, long approvedTotalMinor, long requotedTotalMinor) {
        if (!evaluateRequoteCap(requoteCount).allowed()) {
            return new RequoteOutcome.Rejected(requoteCount, config.maxRequotes());
        }
        return new RequoteOutcome.ApprovalRequired(approvedTotalMinor, requotedTotalMinor);
    }
}
