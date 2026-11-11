package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A merchant checkout session as returned by the merchant (Appendix B). The authoritative quote. {@code buyer} is
 * empty when the merchant reports none.
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
        List<MerchantMessage> messages) {

    public CheckoutView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(buyer, "buyer");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    /** A view with no buyer. */
    public CheckoutView(
            SessionId id, CheckoutStatus status, String currency, List<LineView> lines, long subtotalMinor,
            long taxMinor, long totalMinor, Optional<OrderRef> order, List<MerchantMessage> messages) {
        this(id, status, currency, lines, subtotalMinor, taxMinor, totalMinor, order, Optional.empty(), messages);
    }
}
