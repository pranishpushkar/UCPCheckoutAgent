package dev.ucplab.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.ucp.CatalogClient;
import dev.ucplab.ucp.CircuitBreakers;
import dev.ucplab.ucp.McpCatalogGateway;
import dev.ucplab.ucp.McpMerchantGateway;
import dev.ucplab.ucp.UcpConfig;
import dev.ucplab.ucp.UcpHttpGateway;
import dev.ucplab.ucp.auth.ClientCredentials;
import dev.ucplab.ucp.registry.BuyerIp;
import dev.ucplab.ucp.registry.MerchantSettings;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * What a merchant's file entry becomes at build time (DESIGN 19, 8.5, 26.2): its instrument, the handler its gateway
 * requires, and the refusal when the two disagree. A token is resolved from the environment by {@code token_ref} and is
 * never in a message (I8).
 */
class MerchantWiringTest {

    private static final MerchantId A = new MerchantId("merchant-a");
    private static final String TOKEN = "tok_planted_canary_9f3a";
    private static final PaymentInstrument LAB_CARD = PaymentInstrument.mockCard("pi_lab", "tok_ok");

    private static MerchantSettings settings(Optional<MerchantSettings.PaymentHandler> handler) {
        return new MerchantSettings(A, "Shop", URI.create("http://127.0.0.1:9101"), MerchantStatus.ACTIVE, Optional.empty(),
                Set.of(), MerchantSettings.DEFAULT_VERSIONS, handler);
    }

    private static final MerchantSettings.PaymentHandler SAMPLE = new MerchantSettings.PaymentHandler(
            "mock_payment_handler", "card", "token", "env:SAMPLE_CARD_TOKEN");

    // Covers: 19, 8.5 (a merchant with no payment_handler pays with the lab's default mock card, and its gateway requires mock_pay_1)
    @Test
    void aMerchantWithNoHandler_usesTheDefaultInstrument_andTheLabHandler() {
        MerchantSettings s = settings(Optional.empty());

        assertThat(MerchantWiring.instrument(s, LAB_CARD, Environment.of(Map.of()))).isSameAs(LAB_CARD);
        assertThat(MerchantWiring.requiredHandler(s)).isEqualTo(UcpHttpGateway.PAYMENT_HANDLER);
        MerchantWiring.requireMatchingInstrument(s, LAB_CARD);
    }

    // Covers: 19, 8.5, I8 (a payment_handler builds the instrument from its shape and the token its token_ref names in the
    // environment; the instrument's own text never shows the token)
    @Test
    void aHandler_buildsItsInstrument_fromTheEnvironmentToken() {
        MerchantSettings s = settings(Optional.of(SAMPLE));

        PaymentInstrument i = MerchantWiring.instrument(s, LAB_CARD, Environment.of(Map.of("SAMPLE_CARD_TOKEN", TOKEN)));

        assertThat(i.handlerId()).isEqualTo("mock_payment_handler");
        assertThat(i.type()).isEqualTo("card");
        assertThat(i.credentialType()).isEqualTo("token");
        assertThat(i.token()).isEqualTo(TOKEN);
        assertThat(i.toString()).doesNotContain(TOKEN);
        assertThat(MerchantWiring.requiredHandler(s)).isEqualTo("mock_payment_handler");
        MerchantWiring.requireMatchingInstrument(s, i);
    }

    // Covers: 19, I8 (a token_ref whose variable is unset refuses to build, naming the merchant and the variable, never a value)
    @Test
    void aTokenRefThatIsUnset_refusesToBuild_namingTheVariableOnly() {
        MerchantSettings s = settings(Optional.of(SAMPLE));

        assertThatThrownBy(() -> MerchantWiring.instrument(s, LAB_CARD, Environment.of(Map.of("OTHER", TOKEN))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the token_ref of merchant merchant-a names SAMPLE_CARD_TOKEN, which is not set")
                .message().doesNotContain(TOKEN);
    }

    // Covers: 8.5 (the handler the gateway requires and the instrument's handler must be equal, or the stack does not build:
    // the default instrument for a merchant whose file names another handler, and the reverse)
    @Test
    void aHandlerThatDisagreesWithTheInstrument_refusesToBuild() {
        assertThatThrownBy(() -> MerchantWiring.requireMatchingInstrument(settings(Optional.of(SAMPLE)), LAB_CARD))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("merchant-a")
                .hasMessageContaining("mock_payment_handler").hasMessageContaining("mock_pay_1")
                .message().doesNotContain("tok_ok");
        PaymentInstrument sample = PaymentInstrument.sampleCard("pi_s", TOKEN);
        assertThatThrownBy(() -> MerchantWiring.requireMatchingInstrument(settings(Optional.empty()), sample))
                .isInstanceOf(IllegalStateException.class).message().doesNotContain(TOKEN);
    }

    private static MerchantSettings mcp(boolean buyerIp) {
        return new MerchantSettings(A, "Store", URI.create("https://example-dev.myshopify.com"),
                MerchantStatus.ACTIVE, Optional.empty(), Set.of(), MerchantSettings.DEFAULT_VERSIONS, Optional.empty(),
                dev.ucplab.core.merchant.Transport.MCP, dev.ucplab.core.merchant.Completion.HANDOFF, true,
                Optional.of("example-dev.myshopify.com"), Optional.of("env:SHOPIFY_CLIENT"), buyerIp);
    }

    private static MerchantWiring.GatewayParts parts(Map<String, String> env) {
        UcpConfig ucp = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
        Clock clock = Clock.systemUTC();
        return new MerchantWiring.GatewayParts(ucp, new CircuitBreakers(ucp, clock), clock, d -> { },
                new SplittableRandom(1), new SequentialIdGenerator("w"), () -> call -> {
                    throw new java.io.IOException("no network in this test");
                }, ServiceProperties.DEFAULT_TOKEN_ENDPOINT, Optional.of(new BuyerIp("203.0.113.5")), Environment.of(env));
    }

    // Covers: 19, 8.5, 26.4, H5 C6 (transport picks the gateway: rest builds UcpHttpGateway and its CatalogClient; mcp
    // builds McpMerchantGateway, with the credentials its credential_ref names, and McpCatalogGateway. Until C7 an MCP
    // merchant still refuses to build a stack, by HANDOFF_NOT_WIRED; nothing here is sent)
    @Test
    void transport_picksTheGateway() {
        MerchantWiring.GatewayParts p = parts(Map.of("SHOPIFY_CLIENT", "client-1:secret-1"));

        MerchantGateway rest = MerchantWiring.gateway(settings(Optional.empty()), p);
        MerchantGateway store = MerchantWiring.build(mcp(true), p);

        assertThat(rest).isInstanceOf(UcpHttpGateway.class);
        assertThat(MerchantWiring.catalog(rest, p)).isInstanceOf(CatalogClient.class);
        assertThat(store).isInstanceOf(McpMerchantGateway.class);
        assertThat(((McpMerchantGateway) store).merchant().shopDomain()).contains("example-dev.myshopify.com");
        assertThat(MerchantWiring.catalog(store, p)).isInstanceOf(McpCatalogGateway.class);
        assertThat(store.toString()).doesNotContain("secret-1");
        assertThatThrownBy(() -> MerchantWiring.gateway(mcp(false), p)).hasMessage(MerchantWiring.HANDOFF_NOT_WIRED);
    }

    // Covers: 8.4, 19 (Shopify-Buyer-IP goes to a merchant whose entry says requires_buyer_ip, and to no other: the
    // discovery GET's headers, recorded before the call fails; nothing leaves the JVM)
    @Test
    void buyerIp_goesOnlyToAMerchantThatAsksForIt() {
        java.util.List<java.util.Map<String, String>> sent = new java.util.ArrayList<>();
        UcpConfig ucp = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
        Clock clock = Clock.systemUTC();
        MerchantWiring.GatewayParts p = new MerchantWiring.GatewayParts(ucp, new CircuitBreakers(ucp, clock), clock,
                d -> { }, new SplittableRandom(1), new SequentialIdGenerator("w"), () -> call -> {
                    sent.add(call.headers());
                    throw new java.io.IOException("no network in this test");
                }, ServiceProperties.DEFAULT_TOKEN_ENDPOINT, Optional.of(new BuyerIp("203.0.113.5")),
                Environment.of(Map.of("SHOPIFY_CLIENT", "client-1:secret-1")));

        for (boolean asks : new boolean[] {true, false}) {
            sent.clear();
            MerchantGateway g = MerchantWiring.build(mcp(asks), p);
            assertThatThrownBy(() -> g.discover(A)).isInstanceOf(dev.ucplab.core.merchant.DiscoveryFailedException.class);
            assertThat(sent).as("asks: " + asks).isNotEmpty().allSatisfy(h -> assertThat(
                    h.get(dev.ucplab.ucp.mcp.StoreHeaders.BUYER_IP_HEADER)).isEqualTo(asks ? "203.0.113.5" : null));
        }
    }

    // Covers: 26.4, I8 (a credential value that does not split is refused while building, and the reason never holds it)
    @Test
    void mcp_malformedCredential_isRefused_withoutTheValue() {
        assertThatThrownBy(() -> MerchantWiring.build(mcp(false), parts(Map.of("SHOPIFY_CLIENT", "nocolon_canary"))))
                .hasMessage(ClientCredentials.MALFORMED).message().doesNotContain("canary");
    }

    // Covers: 19, 7.2, H5 C9 (a handoff merchant refuses to build a stack until its orchestrator path (C7) exists:
    // never a complete at a handoff merchant)
    @Test
    void handoffMerchant_refusesToBuild_untilItsPathExists() {
        MerchantSettings restHandoff = new MerchantSettings(A, "Shop", URI.create("http://127.0.0.1:9101"),
                MerchantStatus.ACTIVE, Optional.empty(), Set.of(), MerchantSettings.DEFAULT_VERSIONS, Optional.empty(),
                dev.ucplab.core.merchant.Transport.REST, dev.ucplab.core.merchant.Completion.HANDOFF, true,
                Optional.empty(), Optional.empty(), false);

        assertThatThrownBy(() -> MerchantWiring.requireBuildable(mcp(false))).hasMessage(MerchantWiring.HANDOFF_NOT_WIRED);
        assertThatThrownBy(() -> MerchantWiring.requireBuildable(restHandoff))
                .hasMessage(MerchantWiring.HANDOFF_NOT_WIRED);
        MerchantWiring.requireBuildable(settings(Optional.empty()));
    }

    private static MerchantSettings at(MerchantId id, boolean owned, Completion completion) {
        return new MerchantSettings(id, "Shop", URI.create("http://127.0.0.1:9101"), MerchantStatus.ACTIVE,
                Optional.empty(), Set.of(), MerchantSettings.DEFAULT_VERSIONS, Optional.empty(), Transport.REST,
                completion, owned, Optional.empty(), Optional.empty(), false);
    }

    // Covers: 19, I12, 10 (startup accepts intents in flight whose stored fingerprints are all the entry's now, and a
    // configured merchant with no intent in flight)
    @Test
    void inFlightIntents_underTheEntryTheyStartedWith_startUp() {
        MerchantSettings a = at(A, true, Completion.AGENT);
        MerchantId b = new MerchantId("merchant-b");

        MerchantWiring.requireInFlightMerchantsConfigured(Map.of(A, Set.of(a.entry(MerchantStatus.ACTIVE).fingerprint())),
                List.of(a, at(b, true, Completion.AGENT)));
        MerchantWiring.requireInFlightMerchantsConfigured(Map.of(), List.of(a));
    }

    // Covers: 19, I12, N1 (startup refuses, with a fixed message naming neither merchant nor file, an entry whose
    // completion, owned or fingerprint differs from one an intent in flight stored, including one of several)
    @Test
    void inFlightIntents_underAnotherEntry_refuseStartup() {
        MerchantSettings now = at(A, true, Completion.AGENT);
        String handoff = at(A, true, Completion.HANDOFF).entry(MerchantStatus.ACTIVE).fingerprint();
        String unowned = at(A, false, Completion.AGENT).entry(MerchantStatus.ACTIVE).fingerprint();
        String same = now.entry(MerchantStatus.ACTIVE).fingerprint();

        for (Set<String> stored : List.of(Set.of(handoff), Set.of(unowned), Set.of(same, handoff))) {
            assertThatThrownBy(() -> MerchantWiring.requireInFlightMerchantsConfigured(Map.of(A, stored), List.of(now)))
                    .as(stored.toString()).isInstanceOf(IllegalStateException.class)
                    .hasMessage(MerchantWiring.IN_FLIGHT_MERCHANT_CHANGED);
        }
    }

    // Covers: 19 (a merchant with intents in flight that the file no longer lists still refuses startup with its own
    // reason, checked before the fingerprint)
    @Test
    void inFlightIntents_atARemovedMerchant_refuseStartup() {
        assertThatThrownBy(() -> MerchantWiring.requireInFlightMerchantsConfigured(
                Map.of(new MerchantId("gone"), Set.of(MerchantEntry.LAB_FINGERPRINT)),
                List.of(at(A, true, Completion.AGENT))))
                .isInstanceOf(IllegalStateException.class).hasMessage(MerchantWiring.IN_FLIGHT_MERCHANT_REMOVED);
    }
}

