package dev.ucplab.ucp;

import static dev.ucplab.ucp.McpMerchantGatewayTest.BASE;
import static dev.ucplab.ucp.McpMerchantGatewayTest.CANCEL_KEY;
import static dev.ucplab.ucp.McpMerchantGatewayTest.CONFIG;
import static dev.ucplab.ucp.McpMerchantGatewayTest.CREATE_KEY;
import static dev.ucplab.ucp.McpMerchantGatewayTest.DOMAIN;
import static dev.ucplab.ucp.McpMerchantGatewayTest.ONE_BOARD;
import static dev.ucplab.ucp.McpMerchantGatewayTest.RECORDED;
import static dev.ucplab.ucp.McpMerchantGatewayTest.SHOP;
import static dev.ucplab.ucp.McpMerchantGatewayTest.UPDATE_KEY;
import static dev.ucplab.ucp.McpMerchantGatewayTest.entry;
import static dev.ucplab.ucp.McpMerchantGatewayTest.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.DiscoveryFailedException;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.UpdateCheckout;
import dev.ucplab.ucp.auth.ClientCredentials;
import dev.ucplab.ucp.auth.TokenProvider;
import dev.ucplab.ucp.mcp.McpStore;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * H5 C6 ★: the MCP gateway's decisions one by one (8.3, 8.5, 19, 20, 26.2, 26.4, I8, I12), each test naming the mutants
 * it kills by method and line, as {@link UcpHttpGatewayMutationTest} does for REST.
 */
class McpMerchantGatewayMutationTest {

    private final McpMerchantGatewayTest setup = new McpMerchantGatewayTest();
    private final McpStore store = setup.store;
    private final TestClock clock = setup.clock;

    private static UcpConfig noRetries() {
        return new UcpConfig(CONFIG.ucpAgent(), CONFIG.connectTimeout(), CONFIG.requestTimeout(), CONFIG.completeTimeout(), 0,
                CONFIG.backoffInitial(), CONFIG.backoffCap(), CONFIG.breakerThreshold(), CONFIG.breakerOpenFor(),
                CONFIG.maxResponseBytes());
    }

    private McpMerchantGateway gateway(UcpConfig config, CircuitBreakers breakers, MerchantEntry entry,
                                       ClientCredentials credentials, Set<String> endpointHosts, Set<String> versions) {
        TokenProvider tokens = new TokenProvider(URI.create("https://api.shopify.com/auth/access_token"), credentials,
                setup.tokenEndpoint, clock, Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofSeconds(5));
        return new McpMerchantGateway(entry, config, store, breakers, clock, d -> { }, new SplittableRandom(3),
                new SequentialIdGenerator("m"), endpointHosts, "mock_pay_1", versions, "2026-04-08", tokens, credentials,
                Optional.empty());
    }

    private McpMerchantGateway gateway(MerchantEntry entry, Set<String> endpointHosts) {
        return gateway(noRetries(), new CircuitBreakers(noRetries(), clock), entry, new ClientCredentials("client-1", "secret-1"),
                endpointHosts, Set.of("2026-04-08"));
    }

    private McpMerchantGateway gateway() {
        return gateway(entry(Completion.HANDOFF), Set.of());
    }

    private static String profileWithEndpoint(String endpoint) {
        return McpStore.fixture("store_profile.json").replace("https://" + DOMAIN + "/api/ucp/mcp", endpoint);
    }

    private long gets() {
        return store.sent().stream().filter(s -> s.method().equals("GET")).count();
    }

    // ---------------------------------------------------------------- construction and binding

    // Covers: 26.2, 8.4 (a merchant is pinned to at least one version the agent supports, and the UCP-Agent setting
    // names the profile the envelope carries). Kills McpMerchantGateway.<init>:138, :142 and :148 RemoveConditional
    @Test
    void construction_refusesNoPinnedVersion_anUnsupportedOne_andAnAgentWithoutAProfile() {
        ClientCredentials c = new ClientCredentials("client-1", "secret-1");
        CircuitBreakers b = new CircuitBreakers(CONFIG, clock);

        assertThatThrownBy(() -> gateway(CONFIG, b, entry(Completion.HANDOFF), c, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one UCP version");
        assertThatThrownBy(() -> gateway(CONFIG, b, entry(Completion.HANDOFF), c, Set.of(), Set.of("1999-01-01")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported UCP version 1999-01-01");
        assertThatThrownBy(() -> gateway(UcpConfig.defaults("ucplab-agent/1.0"), b, entry(Completion.HANDOFF), c, Set.of(),
                Set.of("2026-04-08"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("names no profile");
    }

    // Covers: 8.5 (a gateway answers for its own merchant only). Kills McpMerchantGateway.discover:181 RemoveConditional
    @Test
    void discover_ofAnotherMerchant_isRefused_andSendsNothing() {
        assertThatThrownBy(() -> gateway().discover(new MerchantId("other")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bound to dev-store");
        assertThat(store.sent()).isEmpty();
    }

    // Covers: I8 (the gateway's own text names the merchant and nothing else). Kills McpMerchantGateway.toString:407
    @Test
    void toString_namesTheMerchantOnly() {
        assertThat(gateway()).hasToString("McpMerchantGateway[dev-store]");
    }

    // ---------------------------------------------------------------- the profile cache (19, 20)

    // Covers: 19, 20 (a profile is served from the cache for five minutes and read again after). Kills
    // McpMerchantGateway.discover:185 RemoveConditional, :186 NullReturnVals and fresh:194 (NO_COVERAGE)
    @Test
    void aProfile_isCachedForFiveMinutes_thenReadAgain() {
        McpMerchantGateway g = gateway();

        MerchantProfile first = g.discover(SHOP);
        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertThat(g.discover(SHOP)).as("from the cache").isEqualTo(first);
        assertThat(gets()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        g.discover(SHOP);
        assertThat(gets()).isEqualTo(2);
    }

    // Covers: 19, 20, 8.5 (a stale profile that cannot be read again is not served to discovery or to a new intent's
    // create, which sends nothing; a call for an intent in flight keeps the profile it had). Kills
    // McpMerchantGateway.profileFor:251 RemoveConditional (both operands) and send:309
    @Test
    void aStaleProfile_isNeverServedAfterAFailedRefresh_exceptToACallInFlight() {
        McpMerchantGateway g = gateway();
        g.discover(SHOP);
        clock.advance(Duration.ofMinutes(6));
        store.profile(500, "{}");
        store.answer("cancel_checkout", "cancel_checkout.json");

        assertThatThrownBy(() -> g.discover(SHOP)).isInstanceOfSatisfying(DiscoveryFailedException.class,
                e -> assertThat(e.reason()).isEqualTo(UcpHttpGateway.UNREACHABLE));
        assertThat(g.create(ONE_BOARD, CREATE_KEY)).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                n -> assertThat(n.cause()).startsWith("merchant profile unavailable: "));
        assertThat(store.toolCalls()).isEmpty();

        assertThat(ok(g.cancel(RECORDED, CANCEL_KEY)).status()).isEqualTo(CheckoutStatus.CANCELED);
        assertThat(gets()).as("the cancel did not read the profile again").isEqualTo(3);
    }

    // Covers: 20, 8.5 (a 4xx other than 429 of a create may mean the profile changed: the next create reads it again; a
    // 429, a 503 with a Retry-After too long to wait (a ProtocolError that is not a 4xx), or a 4xx of a call in flight,
    // does not). Kills McpMerchantGateway.send:331, :332 (the `newIntent`, 400, 500 and 429 operands) and :333
    // VoidMethodCall
    @Test
    void onlyA4xxOtherThan429OfACreate_makesTheNextCreateReadTheProfileAgain() {
        McpMerchantGateway g = gateway();
        store.raw("create_checkout", 429, "{}").answer("create_checkout", "create_checkout.json");
        assertThat(g.create(ONE_BOARD, CREATE_KEY)).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.httpStatus()).isEqualTo(429));
        ok(g.create(ONE_BOARD, CREATE_KEY));
        assertThat(gets()).as("a 429 keeps the profile").isEqualTo(1);

        store.raw("create_checkout", 503, "{}", "Retry-After", "3600").answer("create_checkout", "create_checkout.json");
        assertThat(g.create(ONE_BOARD, CREATE_KEY)).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.httpStatus()).isEqualTo(503));
        ok(g.create(ONE_BOARD, CREATE_KEY));
        assertThat(gets()).as("a 503 keeps the profile").isEqualTo(1);

        store.raw("update_checkout", 400, "{}");
        assertThat(g.update(RECORDED, UpdateCheckout.of(ONE_BOARD.lines(), Optional.empty()), UPDATE_KEY))
                .isInstanceOf(CallOutcome.ProtocolError.class);
        store.answer("create_checkout", "create_checkout.json");
        ok(g.create(ONE_BOARD, CREATE_KEY));
        assertThat(gets()).as("a refused update keeps the profile").isEqualTo(1);

        store.raw("create_checkout", 400, "{}").answer("create_checkout", "create_checkout.json");
        assertThat(g.create(ONE_BOARD, CREATE_KEY)).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.httpStatus()).isEqualTo(400));
        ok(g.create(ONE_BOARD, CREATE_KEY));
        assertThat(gets()).as("a 400 of a create drops it").isEqualTo(2);
    }

    // Covers: 8.5, I12, I8 (the endpoint is checked again before every call, a get and a catalog read included, since
    // each carries the bearer token and the buyer IP: one not allowed is refused before a token is asked for, and
    // nothing is sent). Kills McpMerchantGateway.send:314 RemoveConditional and :316 NullReturnVals, and read:356
    // and :358
    @Test
    void theReCheck_refusesEveryCall_toAnEndpointNotAllowed_andSendsNothing() {
        MerchantProfile off = new MerchantProfile(SHOP, "2026-04-08",
                URI.create("https://other-store.myshopify.com/api/ucp/mcp"), Set.of(UcpHttpGateway.CHECKOUT_CAPABILITY),
                Set.of());
        McpMerchantGateway g = gateway().withUncheckedProfile(off);
        CallOutcome.NotSent<CheckoutView> refused = new CallOutcome.NotSent<>(UcpHttpGateway.ENDPOINT_NOT_ALLOWED);

        assertThat(g.create(ONE_BOARD, CREATE_KEY)).as("create").isEqualTo(refused);
        assertThat(g.update(RECORDED, UpdateCheckout.of(ONE_BOARD.lines(), Optional.empty()), UPDATE_KEY)).as("update")
                .isEqualTo(refused);
        assertThat(g.cancel(RECORDED, CANCEL_KEY)).as("cancel").isEqualTo(refused);
        assertThat(g.get(RECORDED)).as("get").isEqualTo(refused);
        assertThat(g.read("search_catalog", Map.of("catalog", Map.of("query", "x")))).as("catalog read")
                .isEqualTo(new RetryExecutor.Fetched.Failed(refused));
        assertThat(store.sent()).isEmpty();
        assertThat(setup.tokenEndpoint.calls).as("no token was asked for").isEmpty();
    }

    // ---------------------------------------------------------------- what a profile must offer (8.5, 19, I12)

    // Covers: 8.5 (a profile without the checkout capability fails discovery). Kills McpMerchantGateway.incompatible:226
    // RemoveConditional and :227 (NO_COVERAGE)
    @Test
    void aProfileWithoutTheCheckoutCapability_isRefused() {
        store.profile(McpStore.fixture("store_profile.json").replace("\"dev.ucp.shopping.checkout\": [",
                "\"dev.ucp.shopping.checkout_other\": ["));

        assertThatThrownBy(() -> gateway().discover(SHOP)).isInstanceOfSatisfying(DiscoveryFailedException.class,
                e -> assertThat(e.reason()).isEqualTo(UcpHttpGateway.CAPABILITY_MISSING));
    }

    // Covers: 19 (a completion: agent merchant whose profile lists the handler is accepted). Kills
    // McpMerchantGateway.incompatible:232 RemoveConditional (the handler operand)
    @Test
    void anAgentMerchant_whoseProfileListsTheHandler_isAccepted() {
        store.profile(McpStore.fixture("store_profile.json").replace("\"payment_handlers\": {}",
                "\"payment_handlers\": {\"dev.ucp.payment\": [{\"id\": \"mock_pay_1\"}]}"));

        assertThat(gateway(entry(Completion.AGENT), Set.of()).discover(SHOP).paymentHandlers()).containsExactly("mock_pay_1");
    }

    // Covers: 8.5 (the endpoint keeps the base URL's scheme: http on an allowed host and port is still refused). Kills
    // McpMerchantGateway.endpointAllowed:245 RemoveConditional
    @Test
    void anEndpointOnAnAllowedHostAndPort_butAnotherScheme_isRefused() {
        store.profile(profileWithEndpoint("http://" + DOMAIN + ":80/api/ucp/mcp"));

        assertThatThrownBy(() -> gateway(entry(Completion.HANDOFF), Set.of(DOMAIN + ":80")).discover(SHOP))
                .isInstanceOfSatisfying(DiscoveryFailedException.class,
                        e -> assertThat(e.reason()).isEqualTo(UcpHttpGateway.ENDPOINT_NOT_ALLOWED));
        assertThat(BASE.getScheme()).isEqualTo("https");
    }

    // ---------------------------------------------------------------- the circuit breaker (8.3)

    /** Opens the breaker of {@link McpMerchantGatewayTest#SHOP}: creates the store answers 500, through a gateway of its own. */
    private CircuitBreakers openedBreakers() {
        CircuitBreakers shared = new CircuitBreakers(noRetries(), clock);
        McpMerchantGateway opener = gateway(noRetries(), shared, entry(Completion.HANDOFF),
                new ClientCredentials("client-1", "secret-1"), Set.of(), Set.of("2026-04-08"));
        for (int i = 0; i < noRetries().breakerThreshold(); i++) {
            store.raw("create_checkout", 500, "{}");
            assertThat(opener.create(ONE_BOARD, CREATE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        }
        return shared;
    }

    // Covers: 8.3, 19, 7.5 (an open breaker keeps a create from even reading the profile and a cancel from being sent; a
    // call in flight reads the profile it lacks regardless, and the reconciliation read is sent). Kills
    // McpMerchantGateway.profileFor:254 and send:323 RemoveConditional (the two `bypass the breaker` flags)
    @Test
    void anOpenBreaker_blocksACreateAndACancel_butNotTheProfileReadOfACallInFlight_orAGet() {
        CircuitBreakers breakers = openedBreakers();
        int before = store.sent().size();
        McpMerchantGateway g = gateway(noRetries(), breakers, entry(Completion.HANDOFF),
                new ClientCredentials("client-1", "secret-1"), Set.of(), Set.of("2026-04-08"));
        store.answer("cancel_checkout", "cancel_checkout.json").answer("get_checkout", "cancel_checkout.json");

        assertThat(g.create(ONE_BOARD, CREATE_KEY)).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                n -> assertThat(n.cause()).contains("circuit breaker open"));
        assertThat(store.sent()).as("nothing, not even discovery, left").hasSize(before);

        assertThat(g.cancel(RECORDED, CANCEL_KEY)).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                n -> assertThat(n.cause()).contains("circuit breaker open"));
        assertThat(store.sent().subList(before, store.sent().size())).as("the profile only")
                .singleElement().satisfies(s -> assertThat(s.method()).isEqualTo("GET"));

        assertThat(ok(g.get(RECORDED)).status()).isEqualTo(CheckoutStatus.CANCELED);
    }

    // ---------------------------------------------------------------- tokens and the catalog read (26.4, I8)

    // Covers: 26.4, 8.2, I8 (a read refused for its token, when no new token can be had, ends without a second send and
    // without an exception). Kills McpMerchantGateway.withToken:382 RemoveConditional and :383 (NO_COVERAGE)
    @Test
    void get_refusedForItsToken_withNoNewTokenToBeHad_isNotSentAgain() {
        setup.tokenEndpoint.goodFetches = 1;
        store.error("get_checkout", -32000, "AuthenticationFailed");

        assertThat(gateway().get(RECORDED)).isNotInstanceOf(CallOutcome.Ok.class);
        assertThat(store.toolCalls()).hasSize(1);
    }

    // Covers: 8.5, 26.4 (a catalog read with no profile, or with no token, sends no tool call). Kills
    // McpMerchantGateway.read:350, :361 RemoveConditional and :351, :362 (NO_COVERAGE)
    @Test
    void aCatalogRead_withNoProfile_orNoToken_sendsNoToolCall() {
        Map<String, Object> search = Map.of("catalog", Map.of("query", "snowboard"));
        store.profile(500, "{}");
        assertThat(gateway().read("search_catalog", search)).isInstanceOfSatisfying(RetryExecutor.Fetched.Failed.class,
                f -> assertThat(f.outcome()).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                        n -> assertThat(n.cause()).startsWith("merchant profile unavailable: ")));

        store.profile(McpStore.fixture("store_profile.json"));
        setup.tokenEndpoint.status = 401;
        assertThat(gateway().read("search_catalog", search)).isInstanceOfSatisfying(RetryExecutor.Fetched.Failed.class,
                f -> assertThat(f.outcome()).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                        n -> assertThat(n.cause()).startsWith("no access token: ")));
        assertThat(store.toolCalls()).isEmpty();
    }

    // Covers: I8, 26.4 (a refused catalog read is scrubbed: the secret the store echoes, lower-cased into the code, is
    // masked). Kills McpMerchantGateway.scrubbed:376 RemoveConditional
    @Test
    void aRefusedCatalogRead_isScrubbed() {
        McpMerchantGateway g = gateway(noRetries(), new CircuitBreakers(noRetries(), clock), entry(Completion.HANDOFF),
                new ClientCredentials("client-1", "Sekret9"), Set.of(), Set.of("2026-04-08"));
        store.error("search_catalog", -32602, "Sekret9");

        assertThat(g.read("search_catalog", Map.of("catalog", Map.of("query", "x"))))
                .isInstanceOfSatisfying(RetryExecutor.Fetched.Failed.class, f -> assertThat(f.outcome())
                        .isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                                e -> assertThat(e.code()).isEqualTo(Redactor.MASK)));
    }
}
