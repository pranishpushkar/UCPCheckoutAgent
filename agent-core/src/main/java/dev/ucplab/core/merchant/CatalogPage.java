package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Objects;

/** The products of one search page. {@code hasNextPage} says only that the merchant has more; the tool does not page. */
public record CatalogPage(List<CatalogProduct> products, boolean hasNextPage, int otherCurrency) {

    /** A page with nothing left out for its currency. */
    public CatalogPage(List<CatalogProduct> products, boolean hasNextPage) {
        this(products, hasNextPage, 0);
    }

    public CatalogPage {
        products = List.copyOf(Objects.requireNonNull(products, "products"));
        if (otherCurrency < 0) {
            throw new IllegalArgumentException("otherCurrency must not be negative: " + otherCurrency);
        }
    }
}
