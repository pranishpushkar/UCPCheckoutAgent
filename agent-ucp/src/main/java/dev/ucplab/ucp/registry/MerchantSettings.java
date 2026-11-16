package dev.ucplab.ucp.registry;

import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Transport;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One merchant as {@code MERCHANTS_FILE} (or the {@code MERCHANT_URL} fallback) configures it (DESIGN 19). The core
 * {@link MerchantEntry} (Appendix B) carries what the orchestrator needs (transport, completion, owned, shop_domain);
 * what only the service needs lives here too, keyed by the same id.
 *
 * @param fileStatus what the file says; a merchant is ACTIVE only if this is ACTIVE and its discovery check passed
 * @param labUrl the merchant itself, for the local lab's ground-truth reads and resets (never in prod)
 * @param endpointHosts {@code host[:port]} besides the URL's that the profile's checkout endpoint may use (8.5)
 * @param ucpVersions the versions this merchant may be spoken to in (26.2)
 * @param paymentHandler the instrument shape for this merchant; empty means the lab's default mock card
 * @param transport rest or mcp (19)
 * @param completion agent or handoff (19, 7.2)
 * @param owned the resolved value: the file's {@code owned}, else true only on a loopback host (I12)
 * @param shopDomain the store's host, which the MCP endpoint and every continue_url must match (I12)
 * @param credentialRef the client credentials as {@code env:NAME} (26.4); never the credentials themselves
 * @param requiresBuyerIp every call carries {@code Shopify-Buyer-IP} (8.4)
 */
public record MerchantSettings(
        MerchantId id,
        String name,
        URI url,
        MerchantStatus fileStatus,
        Optional<URI> labUrl,
        Set<String> endpointHosts,
        Set<String> ucpVersions,
        Optional<PaymentHandler> paymentHandler,
        Transport transport,
        Completion completion,
        boolean owned,
        Optional<String> shopDomain,
        Optional<String> credentialRef,
        boolean requiresBuyerIp) {

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
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(shopDomain, "shopDomain");
        Objects.requireNonNull(credentialRef, "credentialRef");
    }

    /** A REST merchant completed by the agent, with no credentials or buyer IP, owned only on a loopback host (19). */
    public MerchantSettings(
            MerchantId id, String name, URI url, MerchantStatus fileStatus, Optional<URI> labUrl,
            Set<String> endpointHosts, Set<String> ucpVersions, Optional<PaymentHandler> paymentHandler) {
        this(id, name, url, fileStatus, labUrl, endpointHosts, ucpVersions, paymentHandler, Transport.REST,
                Completion.AGENT, MerchantEntry.isLoopback(url), Optional.empty(), Optional.empty(), false);
    }

    /** The one merchant of the {@code MERCHANT_URL} fallback: every other setting at its default. */
    public static MerchantSettings single(MerchantEntry entry, URI labUrl) {
        return new MerchantSettings(entry.id(), entry.displayName(), entry.baseUrl(), entry.status(),
                Optional.of(labUrl), Set.of(), DEFAULT_VERSIONS, Optional.empty(), entry.transport(), entry.completion(),
                entry.owned(), entry.shopDomain(), Optional.empty(), false);
    }

    /** The core entry for the given status. */
    public MerchantEntry entry(MerchantStatus status) {
        return new MerchantEntry(id, url, name, status, transport, completion, owned, shopDomain);
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
