package dev.ucplab.service.config;

import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantInstruments;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.ucp.CatalogClient;
import dev.ucplab.ucp.CircuitBreakers;
import dev.ucplab.ucp.HttpTransport;
import dev.ucplab.ucp.McpCatalogGateway;
import dev.ucplab.ucp.McpMerchantGateway;
import dev.ucplab.ucp.Sleeper;
import dev.ucplab.ucp.UcpConfig;
import dev.ucplab.ucp.UcpHttpGateway;
import dev.ucplab.ucp.auth.ClientCredentials;
import dev.ucplab.ucp.auth.TokenProvider;
import dev.ucplab.ucp.registry.BuyerIp;
import dev.ucplab.ucp.registry.MerchantSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * What one merchant's settings mean when the stack is built (DESIGN 19, 8.5): the instrument the agent pays it with, the
 * payment handler its gateway requires in the profile, and the check that the two agree. Nothing here reads a token
 * except {@link #instrument}, which takes it from the environment by the name {@code token_ref} gives, and no message
 * carries it (I8).
 */
final class MerchantWiring {

    private MerchantWiring() {
    }

    /** The handler the merchant's profile must list: the file's, else the lab's {@code mock_pay_1}. */
    static String requiredHandler(MerchantSettings settings) {
        return settings.paymentHandler().map(MerchantSettings.PaymentHandler::id).orElse(UcpHttpGateway.PAYMENT_HANDLER);
    }

    /**
     * The merchant's instrument: the file's {@code payment_handler} shape with the token its {@code token_ref}
     * ({@code env:NAME}) names, or the default instrument when the file names no handler.
     *
     * @throws IllegalStateException if the named variable is not set
     */
    static PaymentInstrument instrument(MerchantSettings settings, PaymentInstrument defaultInstrument,
                                        Environment environment) {
        if (settings.paymentHandler().isEmpty()) {
            return defaultInstrument;
        }
        MerchantSettings.PaymentHandler h = settings.paymentHandler().get();
        String variable = h.tokenRef().substring("env:".length());
        String token = environment.get(variable).orElseThrow(() -> new IllegalStateException(
                "the token_ref of merchant " + settings.id().value() + " names " + variable + ", which is not set"));
        return new PaymentInstrument("pi_" + settings.id().value(), h.id(), h.type(), h.credentialType(), token);
    }

    /** 8.5: the gateway's required handler and the instrument's must be equal, or the stack does not build. */
    static void requireMatchingInstrument(MerchantSettings settings, PaymentInstrument instrument) {
        MerchantInstruments.requireMatchingHandler(settings.id(), requiredHandler(settings), instrument);
    }

    /** How long a token without expires_in is taken to last (TODO(VERIFY 1)), and how early it is refreshed (20). */
    static final Duration TOKEN_DEFAULT_LIFETIME = Duration.ofMinutes(10);
    static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(1);

    /** What a gateway is built from besides the merchant's settings (8.5, 26.4). */
    record GatewayParts(UcpConfig ucp, CircuitBreakers breakers, Clock clock, Sleeper sleeper, RandomGenerator random,
                        IdGenerator ids, Supplier<HttpTransport> transports, URI tokenEndpoint, String agentVersion,
                        Optional<BuyerIp> buyerIp, Environment environment) {

        /** The environment holds the credentials and the buyer IP: shown by neither (I8). */
        @Override
        public String toString() {
            return "GatewayParts[tokenEndpoint=" + tokenEndpoint.getHost() + "]";
        }
    }

    /**
     * The merchant's gateway, picked by its transport (19): {@link UcpHttpGateway} for {@code rest};
     * {@link McpMerchantGateway} for {@code mcp}, with its client credentials read from the variable its
     * {@code credential_ref} names (26.4; startup has checked that it is set and splits), a {@link TokenProvider} for the
     * token endpoint, and {@code Shopify-Buyer-IP} only when the entry says {@code requires_buyer_ip}. A
     * {@code completion: handoff} merchant builds like any other: the orchestrator never sends it a complete (7.2).
     */
    static MerchantGateway gateway(MerchantSettings s, GatewayParts p) {
        MerchantEntry entry = s.entry(s.fileStatus());
        if (s.transport() == Transport.REST) {
            return new UcpHttpGateway(entry, p.ucp(), p.transports().get(), p.breakers(), p.clock(), p.sleeper(),
                    p.random(), p.ids(), s.endpointHosts(), requiredHandler(s), s.ucpVersions());
        }
        String variable = s.credentialRef().orElseThrow().substring("env:".length());
        ClientCredentials credentials = ClientCredentials.parse(p.environment().get(variable)
                .orElseThrow(() -> new IllegalStateException(ServiceProperties.CREDENTIAL_UNSET)));
        TokenProvider tokens = new TokenProvider(p.tokenEndpoint(), credentials, p.transports().get(), p.clock(),
                TOKEN_DEFAULT_LIFETIME, TOKEN_REFRESH_MARGIN, p.ucp().requestTimeout());
        return new McpMerchantGateway(entry, p.ucp(), p.transports().get(), p.breakers(), p.clock(), p.sleeper(),
                p.random(), p.ids(), s.endpointHosts(), requiredHandler(s), s.ucpVersions(), p.agentVersion(), tokens,
                credentials,
                s.requiresBuyerIp() ? p.buyerIp() : Optional.empty());
    }

    /** The catalog of a merchant's gateway: over REST {@link CatalogClient}, over MCP {@link McpCatalogGateway}. */
    static CatalogGateway catalog(MerchantGateway gateway, GatewayParts p) {
        return switch (gateway) {
            case UcpHttpGateway rest -> new CatalogClient(rest, p.ucp(), p.transports().get(), p.breakers(), p.clock(),
                    p.sleeper(), p.random(), p.ids());
            case McpMerchantGateway mcp -> new McpCatalogGateway(mcp);
            default -> throw new IllegalArgumentException("no catalog for " + gateway);
        };
    }

    static final String IN_FLIGHT_MERCHANT_REMOVED =
            "an intent in flight names a merchant that MERCHANTS_FILE does not list; restore the merchant or finish its intents";

    static final String IN_FLIGHT_MERCHANT_CHANGED = "MERCHANTS_FILE changes the owned, shop_domain, transport or"
            + " completion of a merchant with intents in flight; restore the entry or finish its intents (19, I12)";

    /**
     * DESIGN 19: every merchant that has an intent in flight (a non-terminal state, or a cancel still owed) must be
     * configured, with the entry those intents started under, or startup refuses: a removed merchant's intents could
     * never reconcile, and a changed entry could send them to a store they were not proposed for (I12; from handoff to
     * agent, a complete, N1). The messages are fixed: they name neither the merchant nor the file.
     */
    static void requireInFlightMerchantsConfigured(Map<MerchantId, Set<String>> inFlight,
                                                   List<MerchantSettings> configured) {
        inFlight.forEach((id, fingerprints) -> {
            MerchantSettings settings = configured.stream().filter(s -> s.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalStateException(IN_FLIGHT_MERCHANT_REMOVED));
            String now = settings.entry(settings.fileStatus()).fingerprint();
            if (!fingerprints.equals(Set.of(now))) {
                throw new IllegalStateException(IN_FLIGHT_MERCHANT_CHANGED);
            }
        });
    }
}
