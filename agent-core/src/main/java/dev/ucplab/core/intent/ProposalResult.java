package dev.ucplab.core.intent;

import dev.ucplab.core.merchant.CheckoutView;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@code propose_purchase} returns (6.2): {@code {intent_id, state, quote?}}. Appendix B names this type
 * without fields. The state is AWAITING_APPROVAL or a failure state. It carries no approval token (I6, I8).
 */
public record ProposalResult(IntentId intentId, IntentState state, Optional<CheckoutView> quote) {

    public ProposalResult {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(quote, "quote");
    }
}
