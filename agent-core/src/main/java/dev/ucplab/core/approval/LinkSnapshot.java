package dev.ucplab.core.approval;

import dev.ucplab.core.merchant.ContinueUrl;
import java.util.Objects;
import java.util.Optional;

/**
 * The link part of a link intent's approval snapshot (9.2, 26.7): the seller's name and domain, the host of its
 * {@code seller.url} ({@code sellerSite}, when it has one), and the {@code checkout_url} exactly as the quote's read
 * returned it. {@code url} shows its host only (I8); it reaches the owner only as {@code handoff_url} (23.2).
 */
public record LinkSnapshot(String sellerName, String sellerDomain, Optional<String> sellerSite, ContinueUrl url) {

    public LinkSnapshot {
        Objects.requireNonNull(sellerName, "sellerName");
        Objects.requireNonNull(sellerDomain, "sellerDomain");
        Objects.requireNonNull(sellerSite, "sellerSite");
        Objects.requireNonNull(url, "url");
    }
}
