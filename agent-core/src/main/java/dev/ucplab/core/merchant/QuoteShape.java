package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The lines (with the merchant's line ids) and the buyer of the last checkout view an intent saw, read back from its
 * stored response summary. It is what an update must carry to replace the session without losing them (8.4). It is
 * never used to decide anything about price or stock, and nothing is fetched to build it (section 20).
 */
public record QuoteShape(List<LineView> lines, Optional<Buyer> buyer, Optional<FulfillmentView> fulfillment) {

    /** A shape with no fulfillment part. */
    public QuoteShape(List<LineView> lines, Optional<Buyer> buyer) {
        this(lines, buyer, Optional.empty());
    }

    public QuoteShape {
        Objects.requireNonNull(fulfillment, "fulfillment");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        Objects.requireNonNull(buyer, "buyer");
    }
}
