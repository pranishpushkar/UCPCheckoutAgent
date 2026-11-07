package dev.ucplab.core.intent;

import java.util.Objects;

/**
 * One line of a purchase proposal (Appendix B, {@code Orchestrator.propose}). Comes from model
 * output, so it carries no price; prices come only from the merchant's quote (9.2). Size limits are
 * policy (P4), not validation, so a large quantity is representable and then denied.
 */
public record ProposedLine(String itemId, int quantity) {

    public ProposedLine {
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1: " + quantity);
        }
    }
}
