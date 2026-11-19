package dev.ucplab.core.intent;

/**
 * The Event column of table 7.2. Where a row lists alternatives ("item_unavailable or
 * out_of_stock"), each alternative is its own constant.
 */
public enum TransitionEvent {
    POLICY_OK,
    POLICY_DENIED,
    QUOTE_READY,
    ITEM_UNAVAILABLE,
    OUT_OF_STOCK,
    CREATE_REJECTED,
    NOT_SENT,
    UNCERTAIN,
    PROTOCOL_ERROR,
    RETRIES_EXHAUSTED,
    APPROVED,
    REJECTED,
    EXPIRED,
    SEND_COMPLETE,
    MERCHANT_COMPLETED,
    PAYMENT_DECLINED,
    PRICE_CHANGED,
    CHECKOUT_INCOMPLETE,
    OBSERVED_COMPLETED,
    OBSERVED_COMPLETE_IN_PROGRESS,
    OBSERVED_READY_FOR_COMPLETE,
    OBSERVED_CANCELED,
    OBSERVED_INCOMPLETE,
    DEADLINE_REACHED,
    APPROVAL_INVALID,
    MERCHANT_NOT_OWNED,
    MERCHANT_CHANGED,
    COMPLETE_UNAVAILABLE,
    /** 7.2 requires_escalation (handoff). */
    REQUIRES_ESCALATION,
    /** 7.2 hand_off. */
    HAND_OFF,
    /** 7.2 hand_off_changed. */
    HAND_OFF_CHANGED,
    /** 7.2 hand_off_gone. */
    HAND_OFF_GONE,
    /** 7.2 hand_off_refused. */
    HAND_OFF_REFUSED,
    /** 7.2 hand_off_unexpected. */
    HAND_OFF_UNEXPECTED,
    /** 7.2 handoff_timeout: 5 minutes since the approval's persisted decision time (7.6). */
    HANDOFF_TIMEOUT,
    /** 7.2 update_rejected: the merchant refused an update with an error envelope (8.1). */
    UPDATE_REJECTED,
    /** 7.2 quote_unsupported (handoff), and fulfillment_unsupported: a readable answer the agent cannot use. */
    QUOTE_UNSUPPORTED,
    /** 7.2 link_refused: link_quantity_unsupported, link_not_allowed or catalog_unreadable at a link merchant. */
    LINK_REFUSED
}
