package dev.ucplab.core.intent;

/** The Side effect column of table 7.2. */
public enum SideEffect {
    NONE,
    /** Event with the rule id. */
    RECORD_POLICY_RULE,
    /** Create approval and snapshot hash. */
    CREATE_APPROVAL,
    /** Cancel session, best effort. */
    CANCEL_SESSION_BEST_EFFORT,
    /** Resend with the same key (safe). */
    RESEND_SAME_KEY,
    /** Consume the approval. */
    CONSUME_APPROVAL,
    /** Cancel session. */
    CANCEL_SESSION,
    /** POST complete. */
    POST_COMPLETE,
    /** Store order id. */
    STORE_ORDER_ID,
    /** PUT update (revision + 1) on the same session, requote_count + 1. A new approval is required. */
    REQUOTE_ON_SAME_SESSION,
    /** Poll again; do not resend. */
    POLL_AGAIN,
    /** Resend complete with the same key and body (I7). */
    RESEND_COMPLETE,
    /** Event listing all attempts. */
    ESCALATION_EVENT,
    /** failure_reason price_unstable, cancel session. */
    REJECT_PRICE_UNSTABLE
}
