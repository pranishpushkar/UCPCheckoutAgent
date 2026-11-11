package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Objects;

/** The products of one search page. {@code hasNextPage} says only that the merchant has more; the tool does not page. */
public record CatalogPage(List<CatalogProduct> products, boolean hasNextPage) {

    public CatalogPage {
        products = List.copyOf(Objects.requireNonNull(products, "products"));
    }
}
