package dev.ucplab.core.merchant;

import java.util.Objects;
import java.util.Optional;

/**
 * A purchasable variant. {@code itemId} is the variant id, which a checkout takes as {@code item.id}. {@code seller} is
 * the seller's name for a variant of a catalog source (26.7), merchant text and untrusted (6.4); empty at a store.
 */
public record CatalogVariant(String itemId, String title, Money price, Availability availability, Optional<String> seller) {

    /** A variant of a store's own catalog: no seller. */
    public CatalogVariant(String itemId, String title, Money price, Availability availability) {
        this(itemId, title, price, availability, Optional.empty());
    }

    public CatalogVariant {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(seller, "seller");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
    }
}
