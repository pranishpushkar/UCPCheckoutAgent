package dev.ucplab.core.merchant;

import java.util.Objects;
import java.util.Optional;

/**
 * One line of a merchant checkout as the merchant reported it. Appendix B names this type without
 * fields; these are the fields an approval snapshot line needs (9.2), plus the merchant's line id, which an update
 * sends back (8.4). {@code title} is the merchant's item title, cleaned, or empty (9.2).
 */
public record LineView(String itemId, int quantity, long unitPriceMinor, Optional<String> lineId, String title) {

    public LineView {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(lineId, "lineId");
        Objects.requireNonNull(title, "title");
    }

    /** A line with no title. */
    public LineView(String itemId, int quantity, long unitPriceMinor, Optional<String> lineId) {
        this(itemId, quantity, unitPriceMinor, lineId, "");
    }

    /** A line with no known merchant line id and no title. */
    public LineView(String itemId, int quantity, long unitPriceMinor) {
        this(itemId, quantity, unitPriceMinor, Optional.empty());
    }
}
