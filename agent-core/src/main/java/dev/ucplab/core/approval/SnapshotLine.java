package dev.ucplab.core.approval;

import java.util.Objects;

/** One line of an approval snapshot (9.2), taken from the merchant's quote. */
public record SnapshotLine(String itemId, int quantity, long unitPriceMinor) {

    public SnapshotLine {
        Objects.requireNonNull(itemId, "itemId");
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
