package dev.ucplab.core.merchant;

import java.util.Objects;
import java.util.Optional;

/**
 * The merchant's order created by a completed checkout. Appendix B names this type without fields. {@code label} is the
 * store's confirmation code for it ({@code order.label}, VERIFY 11), where the store sends one; the order's
 * {@code permalink_url} is never read (I8).
 */
public record OrderRef(String id, Optional<String> label) {

    public OrderRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        if (id.isBlank()) {
            throw new IllegalArgumentException("order id must not be blank");
        }
    }

    /** An order with no confirmation code: the lab merchant's, and any store's that sends none. */
    public OrderRef(String id) {
        this(id, Optional.empty());
    }
}
