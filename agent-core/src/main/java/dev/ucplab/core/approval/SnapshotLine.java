package dev.ucplab.core.approval;

import dev.ucplab.core.merchant.VariantOption;
import java.util.List;
import java.util.Objects;

/**
 * One line of an approval snapshot (9.2), taken from the merchant's quote. {@code title} is the merchant's item title,
 * cleaned, or empty; an empty title is left out of the hashed form. {@code options} is the variant's own options from the
 * same read, such as its size (9.2); an empty list is left out of the hashed form too.
 */
public record SnapshotLine(String itemId, int quantity, long unitPriceMinor, String title, List<VariantOption> options) {

    /** A line with a title and no options. */
    public SnapshotLine(String itemId, int quantity, long unitPriceMinor, String title) {
        this(itemId, quantity, unitPriceMinor, title, List.of());
    }

    /** A line with no title. */
    public SnapshotLine(String itemId, int quantity, long unitPriceMinor) {
        this(itemId, quantity, unitPriceMinor, "");
    }

    public SnapshotLine {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(title, "title");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1: " + quantity);
        }
        if (unitPriceMinor < 0) {
            throw new IllegalArgumentException("unitPriceMinor must not be negative: " + unitPriceMinor);
        }
    }
}
