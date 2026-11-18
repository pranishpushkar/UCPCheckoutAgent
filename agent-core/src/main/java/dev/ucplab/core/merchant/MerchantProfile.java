package dev.ucplab.core.merchant;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

/**
 * The merchant's discovery profile (2.1, section 19). Appendix B names this type without fields; these are
 * the ones the discovery check needs: version, checkout endpoint, capabilities, payment handlers, and the other
 * versions the merchant declares under {@code supported_versions} (26.2; their profile URLs are not kept).
 */
public record MerchantProfile(
        MerchantId merchant,
        String ucpVersion,
        URI checkoutEndpoint,
        Set<String> capabilities,
        Set<String> paymentHandlers,
        Set<String> supportedVersions) {

    public MerchantProfile {
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(ucpVersion, "ucpVersion");
        Objects.requireNonNull(checkoutEndpoint, "checkoutEndpoint");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        paymentHandlers = Set.copyOf(Objects.requireNonNull(paymentHandlers, "paymentHandlers"));
        supportedVersions = Set.copyOf(Objects.requireNonNull(supportedVersions, "supportedVersions"));
    }

    /** A profile that declares one version only. */
    public MerchantProfile(MerchantId merchant, String ucpVersion, URI checkoutEndpoint, Set<String> capabilities,
                           Set<String> paymentHandlers) {
        this(merchant, ucpVersion, checkoutEndpoint, capabilities, paymentHandlers, Set.of());
    }
}
