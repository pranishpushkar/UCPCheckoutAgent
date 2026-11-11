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
    DEADLINE_REACHED
}
