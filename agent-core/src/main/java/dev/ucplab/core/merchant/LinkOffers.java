package dev.ucplab.core.merchant;

/**
 * The orchestrator's read of a catalog source's offer (7.2 quote_ready (link), 26.7): one fresh read, never cached
 * (20), of the item at the catalog {@code merchant}. It is a read: nothing is sent to any seller (I12).
 */
public interface LinkOffers {

    /** No catalog source: every read is NotSent, so a link intent would end quote_retries_exhausted. */
    LinkOffers NONE = new LinkOffers() {
        @Override
        public CallOutcome<LinkOffer> offer(MerchantId merchant, String itemId) {
            return new CallOutcome.NotSent<>("no catalog source is configured");
        }
    };

    CallOutcome<LinkOffer> offer(MerchantId merchant, String itemId);
}
