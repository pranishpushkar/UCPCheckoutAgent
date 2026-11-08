package dev.ucplab.core.merchant;

import java.util.Objects;

/** The merchant's order created by a completed checkout. Appendix B names this type without fields. */
public record OrderRef(String id) {

    public OrderRef {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("order id must not be blank");
        }
    }
}
