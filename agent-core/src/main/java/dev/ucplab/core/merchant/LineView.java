package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One line of a merchant checkout as the merchant reported it. Appendix B names this type without
 * fields; these are the fields an approval snapshot line needs (9.2), plus the merchant's line id, which an update
 * sends back (8.4). {@code title} is the merchant's item title, cleaned, or empty (9.2). {@code options} is the variant's
 * own options when the read gave them (a catalog offer, 26.7); a store's line has none.
 */
public record LineView(String itemId, int quantity, long unitPriceMinor, Optional<String> lineId, String title,
                       List<VariantOption> options) {

    /** A line with a title and no options. */
    public LineView(String itemId, int quantity, long unitPriceMinor, Optional<String> lineId, String title) {
        this(itemId, quantity, unitPriceMinor, lineId, title, List.of());
    }

    public LineView {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(lineId, "lineId");
        Objects.requireNonNull(title, "title");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
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
