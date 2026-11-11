package dev.ucplab.core.merchant;

import java.util.Objects;
import java.util.Optional;

/**
 * One line of a merchant checkout as the merchant reported it. Appendix B names this type without
 * fields; these are the fields an approval snapshot line needs (9.2), plus the merchant's line id, which an update
 * sends back (8.4).
 */
public record LineView(String itemId, int quantity, long unitPriceMinor, Optional<String> lineId) {

    public LineView {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(lineId, "lineId");
    }

    /** A line with no known merchant line id. */
    public LineView(String itemId, int quantity, long unitPriceMinor) {
        this(itemId, quantity, unitPriceMinor, Optional.empty());
    }
}
