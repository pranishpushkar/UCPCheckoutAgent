package dev.ucplab.core.policy;

/**
 * What a re-quote leads to (9.1, table 7.2). Only P8 rejects a re-quote; P7 never does. Each
 * outcome names its rule and reason so it can be stored as a policy event.
 */
public sealed interface RequoteOutcome {

    PolicyRule rule();

    String reason();

    /**
     * P7: the re-quote goes ahead, but the old approval no longer covers it. The intent re-enters
     * QUOTING and, on quote_ready, AWAITING_APPROVAL for a new approval (table 7.2). Never a rejection.
     */
    record ApprovalRequired(long approvedTotalMinor, long requotedTotalMinor) implements RequoteOutcome {

        @Override
        public PolicyRule rule() {
            return PolicyRule.P7;
        }

        @Override
        public String reason() {
            return "re-quoted total " + requotedTotalMinor + " replaces the approved total " + approvedTotalMinor
                    + "; a new approval is required";
        }
    }

    /**
     * P8: the re-quote cap is reached. The intent ends REJECTED with failure_reason
     * {@value RequotePolicy#PRICE_UNSTABLE} and the session is cancelled (table 7.2).
     */
    record Rejected(int requoteCount, int maxRequotes) implements RequoteOutcome {

        @Override
        public PolicyRule rule() {
            return PolicyRule.P8;
        }

        @Override
        public String reason() {
            return RequotePolicy.PRICE_UNSTABLE;
        }
    }
}
