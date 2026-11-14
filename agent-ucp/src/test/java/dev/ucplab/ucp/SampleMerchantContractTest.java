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
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.DiscoveryFailedException;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.SessionId;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * UCP versions per merchant (26.2, 8.5, H1 C4) and the contract of the sample merchant's version, 2026-04-08, on the
 * wire records in docs/interop/fixtures (01, 04 to 09, 34 to 36): no Python, no server, no network.
 */
class SampleMerchantContractTest {

    private static final String V0408 = "2026-04-08";
    private static final String V0825 = "2026-08-25";
    private static final String SAMPLE_HANDLER = "mock_payment_handler";
    private static final MerchantId SAMPLE = new MerchantId("sample");
    private static final MerchantEntry ENTRY = new MerchantEntry(SAMPLE,
            URI.create("http://127.0.0.1:" + InteropFixtures.PORT), "Sample", MerchantStatus.ACTIVE);
    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    private static final IntentId INTENT = new IntentId("intent-1");
    private static final IdempotencyKey CREATE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.CREATE, 0);
    private static final IdempotencyKey COMPLETE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.COMPLETE, 0);
    private static final SessionId SESSION = new SessionId("<id-13>");

    private final TestClock clock = new TestClock();
    private final List<HttpCall> calls = new ArrayList<>();

    /** Answers the sample's routes from the recorded responses; discovery from fixture 01 (or {@code profile}). */
    private HttpTransport sample(String profileBody, String createFixture, String completeFixture, String getFixture) {
        return call -> {
            calls.add(call);
            String path = call.uri().getPath();
            if (path.equals("/.well-known/ucp")) {
                return new HttpReply(200, java.util.Map.of(), profileBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (call.method().equals("POST") && path.equals("/checkout-sessions")) {
                return InteropFixtures.reply(createFixture);
            }
            if (call.method().equals("POST") && path.endsWith("/complete")) {
                return InteropFixtures.reply(completeFixture);
            }
            if (call.method().equals("GET")) {
                return InteropFixtures.reply(getFixture);
            }
            throw new IOException("unscripted " + call.method() + " " + path);
        };
    }

    private static String profile01() {
        return new String(InteropFixtures.reply("01-discovery").body(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private UcpHttpGateway gateway(HttpTransport transport, Set<String> versions) {
        return new UcpHttpGateway(ENTRY, CONFIG, transport, new CircuitBreakers(CONFIG, clock), clock, d -> { },
                new SplittableRandom(3), new SequentialIdGenerator("r"), Set.of(), SAMPLE_HANDLER, versions);
    }

    private UcpHttpGateway gateway(HttpTransport transport) {
        return gateway(transport, Set.of(V0408));
    }

    private static CreateCheckout request() {
        return new CreateCheckout(List.of(new ProposedLine("bouquet_roses", 1)), Optional.of("john.doe@example.com"));
    }

    private HttpCall last(String method) {
        return calls.stream().filter(c -> c.method().equals(method)).reduce((a, b) -> b).orElseThrow();
    }

    // Covers: 26.2, 8.5, R1 (fixture 01 is read as the sample serves it: version 2026-04-08, the REST endpoint at the
    // origin with no path prefix, the checkout capability, the handler ids; a merchant pinned to 2026-04-08 accepts it)
    @Test
    void aProfileAt20260408_isAcceptedForAMerchantPinnedToIt() {
        UcpHttpGateway g = gateway(sample(profile01(), "04-happy-create", "08-happy-complete", "09-get-after-complete"));

        MerchantProfile p = g.discover(SAMPLE);

        assertThat(p.ucpVersion()).isEqualTo(V0408);
        assertThat(p.checkoutEndpoint()).isEqualTo(URI.create("http://127.0.0.1:" + InteropFixtures.PORT));
        assertThat(p.capabilities()).contains("dev.ucp.shopping.checkout", "dev.ucp.shopping.fulfillment");
        assertThat(p.paymentHandlers()).contains(SAMPLE_HANDLER, "google_pay", "shop_pay");
    }

    // Covers: 26.2, 8.5, R1 (the same profile is refused, as incompatible_version, for a merchant pinned only to 2026-08-25,
    // the default pin, and no create is sent)
    @Test
    void aProfileAt20260408_isRefusedForAMerchantPinnedOnlyTo20260825() {
        UcpHttpGateway g = gateway(sample(profile01(), "04-happy-create", "08-happy-complete", "09-get-after-complete"),
                Set.of(V0825));

        assertThatThrownBy(() -> g.discover(SAMPLE)).isInstanceOf(DiscoveryFailedException.class)
                .hasMessageContaining("incompatible_version");
        assertThat(g.create(request(), CREATE_KEY)).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(calls).filteredOn(c -> c.method().equals("POST")).isEmpty();
    }

    // Covers: 26.2, 8.4 (every call after discovery carries the negotiated version: a create, a complete and the
    // reconciliation read on the cached profile all say version="2026-04-08"; the discovery request itself names none)
    @Test
    void everyRequest_carriesTheNegotiatedVersion() {
        UcpHttpGateway g = gateway(sample(profile01(), "04-happy-create", "08-happy-complete", "09-get-after-complete"));

        g.create(request(), CREATE_KEY);
        g.complete(SESSION, CheckoutWire.completeBody(PaymentInstrument.sampleCard("instr_1", "tok")), COMPLETE_KEY);
        g.get(SESSION);

        String expected = CONFIG.ucpAgent() + "; version=\"2026-04-08\"";
        assertThat(last("POST").headers()).containsEntry("UCP-Agent", expected);
        assertThat(calls).filteredOn(c -> !c.uri().getPath().equals("/.well-known/ucp"))
                .hasSize(3).allSatisfy(c -> assertThat(c.headers()).containsEntry("UCP-Agent", expected));
        assertThat(calls.get(0).uri().getPath()).isEqualTo("/.well-known/ucp");
        assertThat(calls.get(0).headers()).containsEntry("UCP-Agent", CONFIG.ucpAgent());
    }

    // Covers: 26.2 (the version sent is the profile's, the highest the pin, the agent and the profile share: a merchant
    // pinned to both whose profile says 2026-08-25 is told 2026-08-25)
    @Test
    void theNegotiatedVersion_isTheOneTheProfileDeclares_withinThePin() {
        String at0825 = profile01().replace("\"2026-04-08\"", "\"2026-08-25\"");
        UcpHttpGateway g = gateway(sample(at0825, "04-happy-create", "08-happy-complete", "09-get-after-complete"),
                Set.of(V0825, V0408));

        assertThat(g.discover(SAMPLE).ucpVersion()).isEqualTo(V0825);
        g.create(request(), CREATE_KEY);

        assertThat(last("POST").headers()).containsEntry("UCP-Agent", CONFIG.ucpAgent() + "; version=\"2026-08-25\"");
    }

    // Covers: 26.2 (a profile at a version outside the agent's set is refused even when the pin lists it is not possible,
    // and when the pin is fine: 2026-01-23 is in neither)
    @Test
    void aProfileAtAVersionOutsideTheAgentsSet_isRefused() {
        String old = profile01().replace("\"2026-04-08\"", "\"2026-01-23\"");
        UcpHttpGateway g = gateway(sample(old, "04-happy-create", "08-happy-complete", "09-get-after-complete"),
                Set.of(V0825, V0408));

        assertThatThrownBy(() -> g.discover(SAMPLE)).isInstanceOf(DiscoveryFailedException.class)
                .hasMessageContaining("incompatible_version");
    }

    // Covers: 26.2 (a pin that names a version the agent does not support, or names none, refuses to build)
    @Test
    void aPinNamingAnUnsupportedVersion_refusesToBuild() {
        HttpTransport t = sample(profile01(), "04-happy-create", "08-happy-complete", "09-get-after-complete");

        assertThatThrownBy(() -> gateway(t, Set.of("2026-01-23"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2026-01-23");
        assertThatThrownBy(() -> gateway(t, Set.of(V0408, "2099-01-01"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway(t, Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 26.2, 8.5 (the agent's set is exactly the two versions that have contract tests)
    @Test
    void theAgentsSupportedVersions_areTheTwoWithContractTests() {
        assertThat(UcpHttpGateway.SUPPORTED_VERSIONS).containsExactlyInAnyOrder(V0825, V0408);
    }

    // Covers: 2026-04-08 contract, 2.1, 8.1 (fixtures 04 and 34: a create is Ok 201 and incomplete with the line and its
    // totals; the version header of fixture 34 is the one the gateway sends)
    @Test
    void create_atTheSample_isIncompleteWithItsLineAndTotals() {
        UcpHttpGateway g = gateway(sample(profile01(), "04-happy-create", "08-happy-complete", "09-get-after-complete"));

        CallOutcome<CheckoutView> out = g.create(request(), CREATE_KEY);

        assertThat(out).isInstanceOfSatisfying(CallOutcome.Ok.class, ok -> {
            CheckoutView v = (CheckoutView) ok.value();
            assertThat(ok.httpStatus()).isEqualTo(201);
            assertThat(v.status()).isEqualTo(CheckoutStatus.INCOMPLETE);
            assertThat(v.currency()).isEqualTo("USD");
            assertThat(v.lines()).singleElement().satisfies(l -> {
                assertThat(l.itemId()).isEqualTo("bouquet_roses");
                assertThat(l.quantity()).isEqualTo(1);
            });
            assertThat(v.totalMinor()).isEqualTo(3500);
        });
        assertThat(InteropFixtures.requestHeader("34-version-2026-04-08", "UCP-Agent")).endsWith("; version=\"2026-04-08\"");
        assertThat(last("POST").headers().get("UCP-Agent")).endsWith("; version=\"2026-04-08\"");
        assertThat(last("POST").uri().getPath()).isEqualTo("/checkout-sessions");
    }

    // Covers: 2026-04-08 contract, 2.1 (fixtures 08 and 09: complete is Ok 200 COMPLETED with the order's id; a GET after
    // it still says COMPLETED with the order)
    @Test
    void complete_andGetAfterIt_atTheSample_areCompletedWithTheOrder() {
        UcpHttpGateway g = gateway(sample(profile01(), "04-happy-create", "08-happy-complete", "09-get-after-complete"));

        CallOutcome<CheckoutView> done = g.complete(SESSION,
                CheckoutWire.completeBody(PaymentInstrument.sampleCard("instr_spike", "tok")), COMPLETE_KEY);
        CallOutcome<CheckoutView> read = g.get(SESSION);

        assertThat(done).isInstanceOfSatisfying(CallOutcome.Ok.class, ok -> {
            CheckoutView v = (CheckoutView) ok.value();
            assertThat(v.status()).isEqualTo(CheckoutStatus.COMPLETED);
            assertThat(v.order()).isPresent();
        });
        assertThat(read).isInstanceOfSatisfying(CallOutcome.Ok.class, ok ->
                assertThat(((CheckoutView) ok.value()).order()).isPresent());
    }

    // Covers: 8.4, 7.2 (fulfillmentOffered comes from the profile the call went out under: the sample's profile lists the
    // fulfillment capability, so its create view says offered, and the lab profile's does not)
    @Test
    void theViewSaysWhetherTheProfileOffersFulfillment() {
        UcpHttpGateway g = gateway(sample(profile01(), "04-happy-create", "08-happy-complete", "09-get-after-complete"));

        CallOutcome<CheckoutView> out = g.create(request(), CREATE_KEY);

        assertThat(out).isInstanceOfSatisfying(CallOutcome.Ok.class, ok -> assertThat(((CheckoutView) ok.value()).fulfillmentOffered()).isTrue());
        String noFulfillment = profile01().replace("dev.ucp.shopping.fulfillment", "dev.ucp.shopping.other");
        UcpHttpGateway lab = gateway(sample(noFulfillment, "04-happy-create", "08-happy-complete", "09-get-after-complete"));
        assertThat(lab.create(request(), CREATE_KEY)).isInstanceOfSatisfying(CallOutcome.Ok.class,
                ok -> assertThat(((CheckoutView) ok.value()).fulfillmentOffered()).isFalse());
    }
}
