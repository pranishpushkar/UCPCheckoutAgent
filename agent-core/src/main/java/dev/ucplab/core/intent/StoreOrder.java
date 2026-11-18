package dev.ucplab.core.intent;

import java.util.Objects;
import java.util.Optional;

/**
 * The order a store reported for a handed-off checkout (7.6 handoff follow-up, VERIFY 7): its id and its confirmation
 * code ({@code order.label}, VERIFY 11). Never the agent's {@code order_id}: the buyer placed it at the store. The label is
 * returned only to the owner and never logged or put in an event (23.2, I8).
 */
public record StoreOrder(String id, Optional<String> label) {

    public StoreOrder {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        if (id.isBlank()) {
            throw new IllegalArgumentException("store order id must not be blank");
        }
    }

    @Override
    public String toString() {
        return "StoreOrder[id=" + id + ", label=" + (label.isPresent() ? "<present>" : "<none>") + ']';
    }
}
