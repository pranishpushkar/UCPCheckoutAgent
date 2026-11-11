package dev.ucplab.core.merchant;

import java.util.Objects;

/** A purchasable variant. {@code itemId} is the variant id, which a checkout takes as {@code item.id}. */
public record CatalogVariant(String itemId, String title, Money price, Availability availability) {

    public CatalogVariant {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(availability, "availability");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
    }
}
