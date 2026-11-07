package dev.ucplab.core.intent;

import java.util.Objects;
import java.util.Optional;

/** What {@code get_purchase_status} returns (6.2): {@code {state, order_id?, failure_reason?}}. */
public record IntentStatus(IntentId intentId, IntentState state, Optional<String> orderId, Optional<String> failureReason) {

    public IntentStatus {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(failureReason, "failureReason");
    }
}
