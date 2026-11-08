package dev.ucplab.core.merchant;

import java.net.URI;
import java.util.Objects;

/** One merchant in the registry (section 19, Appendix B). */
public record MerchantEntry(MerchantId id, URI baseUrl, String displayName, MerchantStatus status) {

    public MerchantEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(status, "status");
    }
}
