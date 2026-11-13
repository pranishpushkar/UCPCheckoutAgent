package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A product with at least one variant, the first being the merchant's featured one. {@code description} is the
 * merchant's plain-text description, when it sends one. Merchant text is untrusted (6.4).
 */
public record CatalogProduct(String id, String title, Optional<String> description, List<CatalogVariant> variants) {

    public CatalogProduct {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        variants = List.copyOf(Objects.requireNonNull(variants, "variants"));
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("a product has at least one variant");
        }
    }

    /** A product without a description. */
    public CatalogProduct(String id, String title, List<CatalogVariant> variants) {
        this(id, title, Optional.empty(), variants);
    }
}
