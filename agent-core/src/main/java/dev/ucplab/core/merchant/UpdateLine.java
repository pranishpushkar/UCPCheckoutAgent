package dev.ucplab.core.merchant;

import java.util.Objects;
import java.util.Optional;

/** One line of an update: the item and quantity, and the merchant's line id when the last view had one (8.4). */
public record UpdateLine(String itemId, int quantity, Optional<String> lineId) {

    public UpdateLine {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(lineId, "lineId");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1: " + quantity);
        }
    }
}
