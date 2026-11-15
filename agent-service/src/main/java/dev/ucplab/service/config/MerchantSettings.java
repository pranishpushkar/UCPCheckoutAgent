package dev.ucplab.service.config;

import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One merchant as {@code MERCHANTS_FILE} (or the {@code MERCHANT_URL} fallback) configures it (DESIGN 19). The core
 * {@link MerchantEntry} (Appendix B) stays as it is; what only the service needs lives here, keyed by the same id.
 *
 * @param fileStatus what the file says; a merchant is ACTIVE only if this is ACTIVE and its discovery check passed
 * @param labUrl the merchant itself, for the local lab's ground-truth reads and resets (never in prod)
 * @param endpointHosts {@code host[:port]} besides the URL's that the profile's checkout endpoint may use (8.5)
 * @param ucpVersions the versions this merchant may be spoken to in (26.2)
 * @param paymentHandler the instrument shape for this merchant; empty means the lab's default mock card
 */
public record MerchantSettings(
        MerchantId id,
        String name,
        URI url,
        MerchantStatus fileStatus,
        Optional<URI> labUrl,
        Set<String> endpointHosts,
        Set<String> ucpVersions,
        Optional<PaymentHandler> paymentHandler) {

    /** The versions of a merchant that pins none (26.2). */
    public static final Set<String> DEFAULT_VERSIONS = Set.of("2026-08-25");

    public MerchantSettings {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(fileStatus, "fileStatus");
        Objects.requireNonNull(labUrl, "labUrl");
        endpointHosts = Set.copyOf(Objects.requireNonNull(endpointHosts, "endpointHosts"));
        ucpVersions = Set.copyOf(Objects.requireNonNull(ucpVersions, "ucpVersions"));
        Objects.requireNonNull(paymentHandler, "paymentHandler");
    }

    /** The one merchant of the {@code MERCHANT_URL} fallback: every other setting at its default. */
    public static MerchantSettings single(MerchantEntry entry, URI labUrl) {
        return new MerchantSettings(entry.id(), entry.displayName(), entry.baseUrl(), entry.status(),
                Optional.of(labUrl), Set.of(), DEFAULT_VERSIONS, Optional.empty());
    }

    /** The core entry for the given status. */
    public MerchantEntry entry(MerchantStatus status) {
        return new MerchantEntry(id, url, name, status);
    }

    /**
     * The instrument shape of a merchant (19): its handler id, type and credential type, and where the token comes
     * from ({@code token_ref}, {@code env:NAME}). The token itself is never in the file or in this record.
     */
    public record PaymentHandler(String id, String type, String credentialType, String tokenRef) {
        public PaymentHandler {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(credentialType, "credentialType");
            Objects.requireNonNull(tokenRef, "tokenRef");
        }
    }
}
