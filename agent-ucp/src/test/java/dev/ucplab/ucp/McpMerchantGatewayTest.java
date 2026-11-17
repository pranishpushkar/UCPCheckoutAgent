package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.DiscoveryFailedException;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.core.merchant.UpdateCheckout;
import dev.ucplab.ucp.auth.ClientCredentials;
import dev.ucplab.ucp.auth.TokenProvider;
import dev.ucplab.ucp.mcp.McpStore;
import dev.ucplab.ucp.mcp.StoreHeaders;
import dev.ucplab.ucp.registry.BuyerIp;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * {@link McpMerchantGateway} against the recorded Shopify answers (H5 C6 ★; 8.4, 8.5, 19, 26.4, I8, I12). Discovery,
 * the headers of every call, the parsing of the recorded checkouts, the 401 rule, the second guard on complete, and the
 * masking of every credential and the buyer IP.
 */
class McpMerchantGatewayTest {

    static final MerchantId SHOP = new MerchantId("dev-store");
    static final String DOMAIN = "example-dev.myshopify.com";
    static final URI BASE = URI.create("https://" + DOMAIN);
    static final URI ENDPOINT = URI.create("https://" + DOMAIN + "/api/ucp/mcp");
    static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp/agent.json\"");
    static final SessionId RECORDED = new SessionId("gid://shopify/Checkout/fakeCheckoutToken0000001?key=<redacted>");
    static final CreateCheckout ONE_BOARD = new CreateCheckout(
            List.of(new ProposedLine("gid://shopify/ProductVariant/51192065753174", 1)), Optional.empty());
    static final IdempotencyKey CREATE_KEY = IdempotencyKey.derive(new IntentId("i-1"), MutatingOp.CREATE, 0);
    static final IdempotencyKey CANCEL_KEY = IdempotencyKey.derive(new IntentId("i-1"), MutatingOp.CANCEL, 0);
    static final IdempotencyKey UPDATE_KEY = IdempotencyKey.derive(new IntentId("i-1"), MutatingOp.UPDATE, 0);
    static final IdempotencyKey COMPLETE_KEY = IdempotencyKey.derive(new IntentId("i-1"), MutatingOp.COMPLETE, 0);

    static MerchantEntry entry(Completion completion) {
        return new MerchantEntry(SHOP, BASE, "Dev store", MerchantStatus.ACTIVE, Transport.MCP, completion, true,
                Optional.of(DOMAIN));
    }

    /**
     * The token endpoint: each fetch hands out the next token, {@code tok-1}, {@code tok-2}, ..., answered with
     * {@code status}; a fetch after the first {@code goodFetches} is answered 500.
     */
    static final class TokenEndpoint implements HttpTransport {
        final List<String> calls = new ArrayList<>();
        int status = 200;
        int goodFetches = Integer.MAX_VALUE;

        @Override
        public HttpReply send(HttpCall call) {
            calls.add(call.uri().toString());
            String body = "{\"access_token\":\"tok-" + calls.size() + "\",\"expires_in\":3600}";
            int s = calls.size() > goodFetches ? 500 : status;
            return new HttpReply(s, Map.of(), body.getBytes(StandardCharsets.UTF_8));
        }
    }

    final McpStore store = new McpStore();
    final TokenEndpoint tokenEndpoint = new TokenEndpoint();
    final TestClock clock = new TestClock();

    McpMerchantGateway gateway(MerchantEntry entry, Optional<BuyerIp> ip, ClientCredentials credentials,
                               Set<String> endpointHosts) {
        TokenProvider tokens = new TokenProvider(URI.create("https://api.shopify.com/auth/access_token"), credentials,
                tokenEndpoint, clock, Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofSeconds(5));
        return new McpMerchantGateway(entry, CONFIG, store, new CircuitBreakers(CONFIG, clock), clock, d -> { },
                new SplittableRandom(3), new SequentialIdGenerator("m"), endpointHosts, "mock_pay_1",
                Set.of("2026-04-08"), "2026-04-08", tokens, credentials, ip);
    }

    McpMerchantGateway gateway(Optional<BuyerIp> ip) {
        return gateway(entry(Completion.HANDOFF), ip, new ClientCredentials("client-1", "secret-1"), Set.of());
    }

    McpMerchantGateway gateway() {
        return gateway(Optional.empty());
    }

    static CheckoutView ok(CallOutcome<CheckoutView> outcome) {
        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        return ((CallOutcome.Ok<CheckoutView>) outcome).value();
    }

    // ---------------------------------------------------------------- binding and discovery

    // Covers: 8.5, 19 (an MCP gateway is only for a transport: mcp entry)
    @Test
    void construction_refusesARestEntry() {
        MerchantEntry rest = new MerchantEntry(SHOP, BASE, "Dev store", MerchantStatus.ACTIVE, Transport.REST,
                Completion.AGENT, true, Optional.empty());

        assertThatThrownBy(() -> gateway(rest, Optional.empty(), new ClientCredentials("c", "s"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 8.5 (the endpoint is the dev.ucp.shopping entry whose transport is mcp; the profile is a plain GET with
    // no Authorization)
    @Test
    void discover_readsTheMcpEndpoint_withAPlainGet() {
        McpMerchantGateway g = gateway();

        assertThat(g.discover(SHOP).checkoutEndpoint()).isEqualTo(ENDPOINT);
        assertThat(store.sent()).singleElement().satisfies(s -> {
            assertThat(s.method()).isEqualTo("GET");
            assertThat(s.uri()).isEqualTo(BASE + "/.well-known/ucp");
            assertThat(s.header("Authorization")).isEmpty();
        });
        assertThat(tokenEndpoint.calls).as("discovery needs no token").isEmpty();
    }

    // Covers: 8.5, I12, 26.5 (an endpoint off shop_domain fails discovery as endpoint_not_allowed, even when the
    // registry lists its host in endpoint_hosts)
    @Test
    void discover_refusesAnEndpointOffTheShopDomain_evenIfListed() {
        store.profile(McpStore.fixture("store_profile.json").replace("https://" + DOMAIN + "/api/ucp/mcp",
                "https://other-store.myshopify.com/api/ucp/mcp"));
        McpMerchantGateway g = gateway(entry(Completion.HANDOFF), Optional.empty(),
                new ClientCredentials("c", "s"), Set.of("other-store.myshopify.com"));

        assertThatThrownBy(() -> g.discover(SHOP)).isInstanceOfSatisfying(DiscoveryFailedException.class,
                e -> assertThat(e.reason()).isEqualTo(UcpHttpGateway.ENDPOINT_NOT_ALLOWED));
    }

    // Covers: 8.5 (an endpoint on shop_domain but another port is not on the allowed host and port: endpoint_not_allowed)
    @Test
    void discover_refusesTheShopDomainOnAnotherPort() {
        store.profile(McpStore.fixture("store_profile.json").replace("https://" + DOMAIN + "/api/ucp/mcp",
                "https://" + DOMAIN + ":8443/api/ucp/mcp"));

        assertThatThrownBy(() -> gateway().discover(SHOP)).isInstanceOfSatisfying(DiscoveryFailedException.class,
                e -> assertThat(e.reason()).isEqualTo(UcpHttpGateway.ENDPOINT_NOT_ALLOWED));
    }

    // Covers: 8.5, 19 (handler_missing applies only to completion: agent: the store's profile names no payment handler,
    // which a handoff merchant accepts and an agent merchant refuses)
    @Test
    void discover_handlerMissing_onlyForAgentCompletion() {
        assertThat(gateway().discover(SHOP).paymentHandlers()).isEmpty();

        McpMerchantGateway agent = gateway(entry(Completion.AGENT), Optional.empty(), new ClientCredentials("c", "s"),
                Set.of());
        assertThatThrownBy(() -> agent.discover(SHOP)).isInstanceOfSatisfying(DiscoveryFailedException.class,
                e -> assertThat(e.reason()).isEqualTo(UcpHttpGateway.HANDLER_MISSING));
    }

    // Covers: 8.5 (a profile whose shopping service has no mcp entry fails discovery as invalid_profile)
    @Test
    void discover_refusesAProfileWithNoMcpEndpoint() {
        store.profile(McpStore.fixture("store_profile.json").replace("\"transport\": \"mcp\"", "\"transport\": \"rest\""));

        assertThatThrownBy(() -> gateway().discover(SHOP)).isInstanceOfSatisfying(DiscoveryFailedException.class,
                e -> {
                    assertThat(e.reason()).isEqualTo(UcpHttpGateway.INVALID_PROFILE);
                    assertThat(e.getMessage()).contains("mcp endpoint missing");
                });
    }

    // Covers: Appendix B, I8 (continue_url is optional; a value that is not a URI reads as none, and is not echoed)
    @Test
    void continueUrl_absentOrNotAUri_readsAsNone() {
        store.answer("create_checkout", "create_checkout.json",
                t -> t.replaceFirst("\"continue_url\": \"[^\"]*\"", "\"continue_url\": \"https://x y\""))
                .answer("cancel_checkout", "cancel_checkout.json",
                        t -> t.replaceFirst("\"continue_url\": \"[^\"]*\",", ""));
        McpMerchantGateway g = gateway();

        assertThat(ok(g.create(ONE_BOARD, CREATE_KEY)).continueUrl()).isEmpty();
        assertThat(ok(g.cancel(RECORDED, CANCEL_KEY)).continueUrl()).isEmpty();
    }

    // Covers: 26.2, 8.5 (a profile that declares the agent's version neither as its version nor in supported_versions
    // fails discovery)
    @Test
    void discover_refusesAProfileThatDoesNotDeclareTheAgentsVersion() {
        store.profile(McpStore.fixture("store_profile.json").replaceFirst("\"2026-04-08\": \"[^\"]*\"", "\"2026-03-01\": \"x\""));

        assertThatThrownBy(() -> gateway().discover(SHOP)).isInstanceOfSatisfying(DiscoveryFailedException.class,
                e -> assertThat(e.reason()).isEqualTo(UcpHttpGateway.INCOMPATIBLE_VERSION));
    }

    // ---------------------------------------------------------------- headers

    // Covers: 8.4, 19, I8 (with requires_buyer_ip, Shopify-Buyer-IP is on every call to the store, discovery included;
    // never on the token call)
    @Test
    void buyerIp_isOnEveryCallToTheStore_whenAsked() {
        McpMerchantGateway g = gateway(Optional.of(new BuyerIp("203.0.113.77")));
        store.answer("create_checkout", "create_checkout.json").answer("cancel_checkout", "cancel_checkout.json");

        ok(g.create(ONE_BOARD, CREATE_KEY));
        ok(g.cancel(RECORDED, CANCEL_KEY));

        assertThat(store.sent()).hasSize(3)
                .allSatisfy(s -> assertThat(s.header(StoreHeaders.BUYER_IP_HEADER)).isEqualTo("203.0.113.77"));
        assertThat(tokenEndpoint.calls).hasSize(1);
    }

    // Covers: 8.4, 19 (without requires_buyer_ip, no call carries the header)
    @Test
    void buyerIp_isOnNoCall_otherwise() {
        McpMerchantGateway g = gateway();
        store.answer("create_checkout", "create_checkout.json").answer("cancel_checkout", "cancel_checkout.json");

        ok(g.create(ONE_BOARD, CREATE_KEY));
        ok(g.cancel(RECORDED, CANCEL_KEY));

        assertThat(store.sent()).hasSize(3).allSatisfy(s -> assertThat(s.header(StoreHeaders.BUYER_IP_HEADER)).isEmpty());
    }

    // Covers: 26.4, 8.4 (every tool call carries the bearer token; the REST UCP-Agent and Idempotency-Key headers are
    // not sent, their values travel in meta)
    @Test
    void toolCalls_carryTheToken_andTheKeyInMeta() {
        McpMerchantGateway g = gateway();
        store.answer("create_checkout", "create_checkout.json");

        ok(g.create(ONE_BOARD, CREATE_KEY));

        McpStore.Sent create = store.toolCalls().getFirst();
        assertThat(create.uri()).isEqualTo(ENDPOINT.toString());
        assertThat(create.header("Authorization")).isEqualTo("Bearer tok-1");
        assertThat(create.header("Idempotency-Key")).isEmpty();
        assertThat(create.header("UCP-Agent")).isEmpty();
        assertThat(create.header("Request-Id")).isNotEmpty();
        Map<?, ?> meta = (Map<?, ?>) create.arguments().get("meta");
        assertThat(meta.get("idempotency-key")).isEqualTo(CREATE_KEY.value());
        assertThat(meta.get("ucp-agent")).isEqualTo(Map.of("profile", "https://agent.example/.well-known/ucp/agent.json"));
        assertThat(create.arguments().get("checkout")).isInstanceOf(Map.class);
    }

    // ---------------------------------------------------------------- parsing the recorded answers

    // Covers: 8.4, Appendix B, FR2 (the recorded create: totals as a list of {type, amount, display_text}, the line, the
    // messages' code and severity, the continue_url, the status; expires_at is not read; fulfillment is offered because
    // the profile lists the capability)
    @Test
    void create_readsTheRecordedAnswer() {
        store.answer("create_checkout", "create_checkout.json");

        CheckoutView v = ok(gateway().create(ONE_BOARD, CREATE_KEY));

        assertThat(v.id()).isEqualTo(RECORDED);
        assertThat(v.status()).isEqualTo(CheckoutStatus.INCOMPLETE);
        assertThat(v.currency()).isEqualTo("USD");
        assertThat(v.lines()).containsExactly(new LineView("gid://shopify/ProductVariant/51192065753174", 1, 69995,
                Optional.of("gid://shopify/CartLine/00000000-0000-4000-8000-000000000001?cart=fakeCheckoutToken0000001")));
        assertThat(v.subtotalMinor()).isEqualTo(69995);
        assertThat(v.totalMinor()).isEqualTo(69995);
        assertThat(v.taxMinor()).isZero();
        assertThat(v.messages()).extracting(MerchantMessage::code).containsExactly(
                "buyer_identity_contact_method_required", "delivery_address_required",
                "delivery_no_delivery_available_for_merchandise_line");
        assertThat(v.messages()).allSatisfy(m -> {
            assertThat(m.type()).isEqualTo("error");
            assertThat(m.severity()).contains("recoverable");
        });
        assertThat(v.continueUrl()).hasValueSatisfying(u -> assertThat(u.value().getHost()).isEqualTo(DOMAIN));
        assertThat(v.fulfillmentOffered()).isTrue();
        assertThat(v.order()).isEmpty();
    }

    // Covers: 8.1 (the error-envelope row), 8.2, 8.4 (the store's recorded refusal of a create, H5 Stage 3: Rejected
    // with its code, sent once, as a definite answer is never retried)
    @Test
    void aRefusedCreate_isRejected_sentOnce_withItsCode() {
        store.answer("create_checkout", "create_checkout_refused.json");

        CallOutcome<CheckoutView> out = gateway().create(ONE_BOARD, CREATE_KEY);

        assertThat(out).isInstanceOfSatisfying(CallOutcome.Rejected.class, r -> assertThat(r.messages())
                .extracting(m -> ((MerchantMessage) m).code()).containsExactly("buyer_identity_email_is_invalid"));
        assertThat(store.toolCalls()).hasSize(1);
    }

    // Covers: 8.1, 7.2 (a create answered requires_escalation is an Ok with that status)
    @Test
    void create_answeredRequiresEscalation_isOkWithThatStatus() {
        store.answer("create_checkout", "create_checkout.json",
                t -> t.replace("\"status\": \"incomplete\"", "\"status\": \"requires_escalation\""));

        assertThat(ok(gateway().create(ONE_BOARD, CREATE_KEY)).status()).isEqualTo(CheckoutStatus.REQUIRES_ESCALATION);
    }

    // Covers: 8.4, A.1 (the recorded cancel: canceled, with no order; the tool's arguments are the session id)
    @Test
    void cancel_readsTheRecordedAnswer() {
        store.answer("cancel_checkout", "cancel_checkout.json");

        CheckoutView v = ok(gateway().cancel(RECORDED, CANCEL_KEY));

        assertThat(v.status()).isEqualTo(CheckoutStatus.CANCELED);
        assertThat(v.order()).isEmpty();
        assertThat(store.toolCalls().getFirst().arguments().get("id")).isEqualTo(RECORDED.value());
    }

    // Covers: 8.4 (an update is update_checkout with the session id and the update body; VERIFY 9)
    @Test
    void update_sendsTheIdAndTheUpdateBody() {
        store.answer("update_checkout", "create_checkout.json");
        UpdateCheckout update = UpdateCheckout.of(ONE_BOARD.lines(), Optional.of("buyer@example.com"));

        ok(gateway().update(RECORDED, update, UPDATE_KEY));

        McpStore.Sent sent = store.toolCalls().getFirst();
        assertThat(sent.tool()).isEqualTo("update_checkout");
        assertThat(sent.arguments().get("id")).isEqualTo(RECORDED.value());
        assertThat(sent.arguments().get("checkout"))
                .isEqualTo(dev.ucplab.core.json.CanonicalJson.readObject(CheckoutWire.updateBody(update)));
    }

    // ---------------------------------------------------------------- no complete

    // Covers: 8.4, N1, 7.2 complete_unavailable (the second guard: complete sends nothing and is NotSent)
    @Test
    void complete_sendsNothing() {
        CallOutcome<CheckoutView> outcome = gateway().complete(RECORDED,
                CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok")), COMPLETE_KEY);

        assertThat(outcome).isEqualTo(new CallOutcome.NotSent<>(McpMerchantGateway.NO_COMPLETE));
        assertThat(store.sent()).isEmpty();
        assertThat(tokenEndpoint.calls).isEmpty();
    }

    // ---------------------------------------------------------------- tokens and the 401 rule

    // Covers: 8.2, 26.4, C5 (a read refused for its token is tried once more with a new token, as a new run: a new
    // Request-Id, the same body bytes)
    @Test
    void get_refusedForItsToken_isTriedOnceMore_withANewToken() {
        store.error("get_checkout", -32000, "AuthenticationFailed").answer("get_checkout", "cancel_checkout.json");

        assertThat(ok(gateway().get(RECORDED)).status()).isEqualTo(CheckoutStatus.CANCELED);

        List<McpStore.Sent> gets = store.toolCalls();
        assertThat(gets).hasSize(2);
        assertThat(gets).extracting(s -> s.header("Authorization")).containsExactly("Bearer tok-1", "Bearer tok-2");
        assertThat(gets.get(0).header("Request-Id")).isNotEqualTo(gets.get(1).header("Request-Id"));
        assertThat(gets.get(0).body()).isEqualTo(gets.get(1).body());
        assertThat(tokenEndpoint.calls).hasSize(2);
    }

    // Covers: 8.2, 26.4 (a read refused twice is the ProtocolError: one retry, not a loop)
    @Test
    void get_refusedTwice_isAProtocolError401() {
        store.error("get_checkout", -32000, "AuthenticationFailed").error("get_checkout", -32000, "AuthenticationFailed");

        assertThat(gateway().get(RECORDED)).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.httpStatus()).isEqualTo(401));
        assertThat(store.toolCalls()).hasSize(2);
    }

    // Covers: 8.2, 26.4, I7 (a mutating call refused for its token is never resent: a 401 ProtocolError after one
    // send; the refused token is dropped, so the next call carries a new one)
    @Test
    void create_refusedForItsToken_isNotResent_andTheTokenIsDropped() {
        store.error("create_checkout", -32000, "AuthenticationFailed").answer("cancel_checkout", "cancel_checkout.json");
        McpMerchantGateway g = gateway();

        assertThat(g.create(ONE_BOARD, CREATE_KEY)).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.httpStatus()).isEqualTo(401));
        assertThat(store.toolCalls()).hasSize(1);

        ok(g.cancel(RECORDED, CANCEL_KEY));
        assertThat(store.toolCalls().get(1).header("Authorization")).isEqualTo("Bearer tok-2");
    }

    // Covers: 26.4, 8.1 (no token can be had: nothing is sent to the store's endpoint, NotSent with the fixed reason)
    @Test
    void noToken_sendsNothingToTheEndpoint() {
        tokenEndpoint.status = 500;

        CallOutcome<CheckoutView> outcome = gateway().create(ONE_BOARD, CREATE_KEY);

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                n -> assertThat(n.cause()).isEqualTo("no access token: token endpoint http 500"));
        assertThat(store.toolCalls()).isEmpty();
    }

    // ---------------------------------------------------------------- I4

    // Covers: I4, 8.1 (an answer about another session is refused: Uncertain for a cancel or an update, ProtocolError
    // for a get)
    @Test
    void anAnswerAboutAnotherSession_isRefused() {
        SessionId other = new SessionId("gid://shopify/Checkout/another?key=<redacted>");
        store.answer("cancel_checkout", "cancel_checkout.json").answer("get_checkout", "cancel_checkout.json")
                .answer("update_checkout", "create_checkout.json");
        McpMerchantGateway g = gateway();

        assertThat(g.cancel(other, CANCEL_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(g.update(other, UpdateCheckout.of(ONE_BOARD.lines(), Optional.empty()), UPDATE_KEY))
                .isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(g.get(other)).isInstanceOf(CallOutcome.ProtocolError.class);
    }

    // ---------------------------------------------------------------- I8

    // Covers: I8, 26.4 (only the secret part of the credential is planted, echoed by the store in a message's content
    // and, lower-cased by the JSON-RPC code rule, in an error: neither comes back; nor does the bearer token or the
    // buyer IP)
    @Test
    void i8_theSecretAloneTheTokenAndTheBuyerIp_neverComeBack() {
        String secret = "SeCrEt_Canary_4b7e";
        String ip = "203.0.113.91";
        McpMerchantGateway g = gateway(entry(Completion.HANDOFF), Optional.of(new BuyerIp(ip)),
                new ClientCredentials("client-1", secret), Set.of());
        store.answer("create_checkout", "create_checkout.json", t -> t.replace("Missing a valid contact method.",
                        "echo " + secret + " tok-1 " + ip))
                .error("cancel_checkout", -32000, secret)
                // -32602 is a 400 (8.4), whose ProtocolError code is the bare message lower-cased: the echo reaches
                // the outcome only in a case the secret does not have.
                .error("get_checkout", -32602, secret);

        List<CallOutcome<CheckoutView>> outcomes = List.of(g.create(ONE_BOARD, CREATE_KEY), g.cancel(RECORDED, CANCEL_KEY),
                g.get(RECORDED));

        assertThat(outcomes.get(1)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(outcomes.get(2)).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.code()).as("the lower-cased echo is the code, masked").isEqualTo(Redactor.MASK));
        for (CallOutcome<CheckoutView> o : outcomes) {
            String text = o.toString().toLowerCase(Locale.ROOT);
            assertThat(text).doesNotContain(secret.toLowerCase(Locale.ROOT)).doesNotContain("tok-1")
                    .doesNotContain(ip);
        }
        assertThat(g.toString()).doesNotContain(secret);
    }

    // Covers: I8, 26.4, 8.4 (a full exchange with every credential planted, echoed back by the store: the secret alone,
    // the whole client_id:client_secret value, the bearer token and the buyer IP reach no log record, no stream and no
    // toString of the new types)
    @Test
    void i8_nothingReachesALogAStreamOrAToString() {
        String secret = "Canary_Secret_e91c";
        String ip = "203.0.113.92";
        List<String> seen = new ArrayList<>();
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        java.util.logging.Level before = root.getLevel();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                seen.add(r.getMessage() + " " + r.getThrown());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        java.io.ByteArrayOutputStream streams = new java.io.ByteArrayOutputStream();
        java.io.PrintStream out = System.out;
        java.io.PrintStream err = System.err;
        root.addHandler(capture);
        root.setLevel(java.util.logging.Level.ALL);
        System.setOut(new java.io.PrintStream(streams, true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(streams, true, StandardCharsets.UTF_8));
        try {
            ClientCredentials credentials = ClientCredentials.parse("client-1:" + secret);
            McpMerchantGateway g = gateway(entry(Completion.HANDOFF), Optional.of(new BuyerIp(ip)), credentials, Set.of());
            store.answer("create_checkout", "create_checkout.json", t -> t.replace("Missing a valid contact method.",
                    "client-1:" + secret + " Bearer tok-1 " + ip)).error("cancel_checkout", -32000, secret)
                    .raw("get_checkout", 500, "{\"error\":{\"code\":-32603,\"message\":\"" + ip + " tok-1\"}}");
            seen.add(g.create(ONE_BOARD, CREATE_KEY).toString());
            seen.add(g.cancel(RECORDED, CANCEL_KEY).toString());
            seen.add(g.get(RECORDED).toString());
            seen.addAll(List.of(g.toString(), credentials.toString(), new McpCatalogGateway(g).toString(),
                    new StoreHeaders(store, Optional.empty(), Optional.of(new BuyerIp(ip))).toString(),
                    new BuyerIp(ip).toString()));
        } finally {
            System.setOut(out);
            System.setErr(err);
            root.removeHandler(capture);
            root.setLevel(before);
        }
        seen.add(streams.toString(StandardCharsets.UTF_8));

        String all = String.join("\n", seen).toLowerCase(Locale.ROOT);
        assertThat(all).doesNotContain(secret.toLowerCase(Locale.ROOT)).doesNotContain("tok-1").doesNotContain(ip);
    }
}
