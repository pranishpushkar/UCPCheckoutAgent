package dev.ucplab.core.policy;

/** The rules of table 9.1 [CONTRACT]. The constant name is the rule id stored in events and metrics. */
public enum PolicyRule {
    /** Merchant allowlist: only ACTIVE merchants in the registry (section 19). */
    P1,
    /** Per-purchase cap on the quoted total. */
    P2,
    /** Daily cap per user on committed spend. */
    P3,
    /** Size limits: quantity per line and number of lines. */
    P4,
    /** Proposal rate limit per user per hour. */
    P5,
    /** Approval required; may not be false in the prod profile. */
    P6,
    /** Re-approval: a re-quote with a higher total needs a new approval. */
    P7,
    /** Re-quote cap per intent; reaching it ends the intent as REJECTED with price_unstable. */
    P8
}
