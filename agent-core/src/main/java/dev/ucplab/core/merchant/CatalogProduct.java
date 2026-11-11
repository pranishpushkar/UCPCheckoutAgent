package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Objects;

/** A product with at least one variant, the first being the merchant's featured one. Merchant text is untrusted. */
public record CatalogProduct(String id, String title, List<CatalogVariant> variants) {

    public CatalogProduct {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        variants = List.copyOf(Objects.requireNonNull(variants, "variants"));
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("a product has at least one variant");
        }
    }
}
