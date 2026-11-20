package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One offer of a catalog source, read fresh for a link intent's quote (7.2 quote_ready (link), 26.7): the variant's id
 * and title, its price, whether it is available, its seller, and its {@code checkout_url} exactly as the catalog sent
 * it, and the variant's own options (9.2). {@link ContinueUrl} shows the link by its scheme and host only (I8).
 */
public record LinkOffer(String itemId, String title, Money price, boolean available, Seller seller,
                        Optional<ContinueUrl> checkoutUrl, List<VariantOption> options) {

    /** An offer whose variant has no options. */
    public LinkOffer(String itemId, String title, Money price, boolean available, Seller seller,
                     Optional<ContinueUrl> checkoutUrl) {
        this(itemId, title, price, available, seller, checkoutUrl, List.of());
    }

    public LinkOffer {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(seller, "seller");
        Objects.requireNonNull(checkoutUrl, "checkoutUrl");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
    }
}
