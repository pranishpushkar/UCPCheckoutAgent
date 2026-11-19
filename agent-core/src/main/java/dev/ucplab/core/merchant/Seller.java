package dev.ucplab.core.merchant;

import java.util.Objects;
import java.util.Optional;

/**
 * The seller of a catalog offer (26.7): {@code name} is its {@code seller.name}, the seller's text and untrusted (6.4);
 * {@code domain} is its {@code seller.domain}; {@code site} is the host of its {@code seller.url}, when it has one. The
 * domain and the site are what I12's link rule compares a {@code checkout_url} with; neither reaches the model (I6).
 */
public record Seller(String name, String domain, Optional<String> site) {

    public Seller {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(site, "site");
    }
}
