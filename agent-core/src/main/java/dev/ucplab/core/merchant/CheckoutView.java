package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A merchant checkout session as returned by the merchant (Appendix B). The authoritative quote. {@code buyer} is
 * empty when the merchant reports none. {@code fulfillment} is the shipping part when the merchant offers one, and
 * {@code fulfillmentOffered} is true when the profile the call went out under lists the fulfillment capability (the
 * gateway sets it; the orchestrator never discovers inside a step); {@code fulfillmentMinor} the amount of its {@code fulfillment} total line (0 when there is none); the total includes it.
 */
public record CheckoutView(
        SessionId id,
        CheckoutStatus status,
        String currency,
        List<LineView> lines,
        long subtotalMinor,
        long taxMinor,
        long totalMinor,
        Optional<OrderRef> order,
        Optional<Buyer> buyer,
        List<MerchantMessage> messages,
        Optional<FulfillmentView> fulfillment,
        long fulfillmentMinor,
        boolean fulfillmentOffered) {

    /** A view that does not say whether the merchant offers fulfillment: it reads as not offered. */
    public CheckoutView(
            SessionId id, CheckoutStatus status, String currency, List<LineView> lines, long subtotalMinor,
            long taxMinor, long totalMinor, Optional<OrderRef> order, Optional<Buyer> buyer,
            List<MerchantMessage> messages, Optional<FulfillmentView> fulfillment, long fulfillmentMinor) {
        this(id, status, currency, lines, subtotalMinor, taxMinor, totalMinor, order, buyer, messages, fulfillment,
                fulfillmentMinor, false);
    }

    /** This view with {@code fulfillmentOffered} set from the profile the call went out under. */
    public CheckoutView withFulfillmentOffered(boolean offered) {
        return new CheckoutView(id, status, currency, lines, subtotalMinor, taxMinor, totalMinor, order, buyer, messages,
                fulfillment, fulfillmentMinor, offered);
    }

    public CheckoutView {
        Objects.requireNonNull(fulfillment, "fulfillment");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(buyer, "buyer");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    /** A view with no fulfillment part (a merchant that offers none, and the lab). */
    public CheckoutView(
            SessionId id, CheckoutStatus status, String currency, List<LineView> lines, long subtotalMinor,
            long taxMinor, long totalMinor, Optional<OrderRef> order, Optional<Buyer> buyer,
            List<MerchantMessage> messages) {
        this(id, status, currency, lines, subtotalMinor, taxMinor, totalMinor, order, buyer, messages, Optional.empty(), 0);
    }

    /** A view with no buyer. */
    public CheckoutView(
            SessionId id, CheckoutStatus status, String currency, List<LineView> lines, long subtotalMinor,
            long taxMinor, long totalMinor, Optional<OrderRef> order, List<MerchantMessage> messages) {
        this(id, status, currency, lines, subtotalMinor, taxMinor, totalMinor, order, Optional.empty(), messages);
    }
}
