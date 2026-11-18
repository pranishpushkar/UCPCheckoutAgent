package dev.ucplab.service.web;

import dev.ucplab.core.merchant.MerchantEntry;
import java.net.URI;
import java.util.Optional;

/**
 * Whether a merchant is a live store (23.2, 23.3, H5): {@code owned: true} with a {@code shop_domain} that is not a
 * loopback host. The intent's answer and the lab merchant list both decide it here, from the values each one has.
 */
public final class LiveStore {

    private LiveStore() {
    }

    /** The store's domain when the merchant is a live store; empty otherwise. */
    public static Optional<String> domain(boolean owned, Optional<String> shopDomain) {
        return shopDomain.filter(d -> owned && !MerchantEntry.isLoopback(URI.create("https://" + d)));
    }
}
