package dev.ucplab.core.merchant;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

/**
 * The merchant's discovery profile (2.1, section 19). Appendix B names this type without fields; these are
 * the ones the discovery check needs: version, checkout endpoint, capabilities and payment handlers.
 */
public record MerchantProfile(
        MerchantId merchant,
        String ucpVersion,
        URI checkoutEndpoint,
        Set<String> capabilities,
        Set<String> paymentHandlers) {

    public MerchantProfile {
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(ucpVersion, "ucpVersion");
        Objects.requireNonNull(checkoutEndpoint, "checkoutEndpoint");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        paymentHandlers = Set.copyOf(Objects.requireNonNull(paymentHandlers, "paymentHandlers"));
    }
}
