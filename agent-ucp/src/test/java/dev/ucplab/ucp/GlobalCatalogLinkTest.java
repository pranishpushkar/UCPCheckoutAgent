package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalSecret;
import dev.ucplab.core.approval.ApprovalTokens;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.approval.InMemoryApprovalRepository;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.HandoffWatch;
import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.FulfillmentPolicy;
import dev.ucplab.core.merchant.LinkOffer;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.Money;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.core.orchestrator.OrchestratorConfig;
import dev.ucplab.core.policy.PolicyConfig;
import dev.ucplab.core.policy.PolicyUsage;
import dev.ucplab.core.policy.RequotePolicy;
import dev.ucplab.core.policy.RuleBasedPolicyEngine;
import dev.ucplab.ucp.mcp.McpStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Real catalog stage 2 end to end, offline: the orchestrator over {@link GlobalCatalogGateway} as its offer read, the
 * catalog a loopback stub answering with the owner-approved fixtures ({@code shopify-catalog/}; 26.7, 7.2, I12 C3). It
 * is in agent-ucp's tests because agent-core must not depend on agent-ucp.
 */
class GlobalCatalogLinkTest {

    static final UserId ALICE = new UserId("alice");
    static final String ITEM = "gid://shopify/ProductVariant/1000001";
    static final List<ProposedLine> ONE = List.of(new ProposedLine(ITEM, 1));

    final GlobalCatalogGatewayTest catalog = new GlobalCatalogGatewayTest();
    final TestClock clock = catalog.clock;
    final InMemoryIntentRepository intents = new InMemoryIntentRepository(clock);
    final InMemoryApprovalRepository approvals = new InMemoryApprovalRepository();
    DefaultApprovalService approvalService;
    DefaultOrchestrator orchestrator;

    /** The orchestrator over the catalog gateway; any checkout call would reach the stub and be seen there. */
    void wire() {
        GlobalCatalogGateway gateway = catalog.gateway();
        MerchantEntry entry = GlobalCatalogGatewayTest.entry();
        PolicyConfig policyConfig = new PolicyConfig(50_000, 100_000, 5, 5, 10, true, 2);
        RuleBasedPolicyEngine policy = new RuleBasedPolicyEngine(policyConfig,
                m -> m.equals(entry.id()) ? Optional.of(entry) : Optional.empty(),
                new PolicyUsage() {
                    @Override
                    public long committedSpendMinor(UserId user, Instant from, Instant to) {
                        return 0;
                    }

                    @Override
                    public int proposalCount(UserId user, Instant from, Instant to) {
                        return 0;
                    }
                }, clock);
        SequentialIdGenerator ids = new SequentialIdGenerator("link");
        OrchestratorConfig config = OrchestratorConfig.defaults();
        approvalService = new DefaultApprovalService(intents, approvals,
                new ApprovalTokens(ApprovalSecret.of("0123456789abcdef0123456789abcdef")), clock, ids, "worker-1",
                config.lease(), DefaultApprovalService.DEFAULT_TTL, DefaultApprovalService.DEFAULT_MAX_AUTH_AGE);
        orchestrator = new DefaultOrchestrator(intents, approvals, approvalService, m -> (MerchantGateway) gateway,
                m -> m.equals(entry.id()) ? Optional.of(entry) : Optional.empty(), gateway, policy,
                new RequotePolicy(policyConfig), m -> PaymentInstrument.mockCard("pi_1", "tok_ok"),
                FulfillmentPolicy.cheapest(), config, clock, ids, "worker-1", new SplittableRandom(42));
    }

    AuthContext alice() {
        return new AuthContext(ALICE, "alice@example.com", clock.instant(), "session-alice");
    }

    IntentState drive(IntentId id) {
        for (int i = 0; i < 50; i++) {
            StepResult r = orchestrator.advance(id);
            if (r.idle()) {
                return r.after();
            }
            r.nextAttemptAt().filter(at -> at.isAfter(clock.instant()))
                    .ifPresent(at -> clock.advance(java.time.Duration.between(clock.instant(), at)));
        }
        throw new AssertionError("did not settle");
    }

    PurchaseIntent intent(IntentId id) {
        return intents.find(ALICE, id).orElseThrow();
    }

    /** The fixture's get_product answer, its one variant's checkout_url changed by {@code link}. */
    @SuppressWarnings("unchecked")
    static String withCheckoutUrl(String link) {
        Map<String, Object> answer = LenientJson.readObject(McpStore.catalogFixture("global_get_product_us.json"));
        Map<String, Object> variant = ((List<Map<String, Object>>) ((Map<String, Object>) GlobalCatalogGatewayTest
                .content(answer).get("product")).get("variants")).getFirst();
        variant.put("checkout_url", link);
        return McpStore.json(answer);
    }

    @SuppressWarnings("unchecked")
    static String fixtureLink() {
        Map<String, Object> answer = LenientJson.readObject(McpStore.catalogFixture("global_get_product_us.json"));
        return (String) ((List<Map<String, Object>>) ((Map<String, Object>) GlobalCatalogGatewayTest.content(answer)
                .get("product")).get("variants")).getFirst().get("checkout_url");
    }

    void noFullLinkIn(IntentId id) {
        for (IntentEvent e : intents.events(id)) {
            assertThat(e.payloadJson()).as(e.type()).doesNotContain("_gsid").doesNotContain("FAKECLICK")
                    .doesNotContain("FAKEGSID").doesNotContain("/buy/");
        }
    }

    void noCheckoutCallSent() {
        assertThat(catalog.store.toolCalls()).extracting(McpStore.Sent::tool).containsOnly("get_product");
    }

    // ---------------------------------------------------------------- the offer read

    // Covers: 26.7, 7.2 quote_ready (link), I6 (the offer read is get_product of the variant: its title, USD price,
    // availability, the seller's name, domain and site, and the checkout_url exactly as sent)
    @Test
    void offer_readsTheVariantsSellerAndLink_exactly() {
        catalog.store.answerText("get_product", McpStore.catalogFixture("global_get_product_us.json"));

        CallOutcome<LinkOffer> outcome = catalog.gateway().offer(GlobalCatalogGatewayTest.CATALOG, ITEM);

        LinkOffer offer = GlobalCatalogGatewayTest.ok(outcome);
        assertThat(offer.itemId()).isEqualTo(ITEM);
        assertThat(offer.title()).isEqualTo("Example Snowboard 1");
        assertThat(offer.price()).isEqualTo(new Money(30495, "USD"));
        assertThat(offer.available()).isTrue();
        assertThat(offer.seller().name()).isEqualTo("Example Shop A");
        assertThat(offer.seller().domain()).isEqualTo("shop-a.myshopify.example");
        assertThat(offer.seller().site()).contains("www.shop-a.example");
        assertThat(offer.checkoutUrl().orElseThrow().value().toString()).isEqualTo(fixtureLink());
        assertThat(catalog.store.toolCalls()).extracting(McpStore.Sent::tool).containsExactly("get_product");
    }

    // Covers: 26.7, 7.2 item_unavailable (link) (a variant id the answer does not hold is Rejected, never another
    // variant's offer)
    @Test
    void offer_ofAVariantNotInTheAnswer_isRejected() {
        catalog.store.answerText("get_product", McpStore.catalogFixture("global_get_product_us.json"));

        assertThat(catalog.gateway().offer(GlobalCatalogGatewayTest.CATALOG, "gid://shopify/ProductVariant/9999999"))
                .isInstanceOf(CallOutcome.Rejected.class);
    }

    // ---------------------------------------------------------------- C3 through the orchestrator

    // Covers: 7.2 quote_ready (link), hand_off (link), I12 C3, I3, I8, 26.7 (the fixture as it is: its link is on the
    // host of seller.url, so it is approved and handed off exactly as returned, attribution included; no checkout call)
    @Test
    void theFixturesLink_isApproved_andHandedOffExactly() {
        catalog.store.answerText("get_product", McpStore.catalogFixture("global_get_product_us.json"));
        wire();
        IntentId id = orchestrator.propose(alice(), GlobalCatalogGatewayTest.CATALOG, ONE, "a board").intentId();
        assertThat(drive(id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        PendingApproval pending = approvalService.pending(alice(), id).orElseThrow();
        assertThat(approvalService.decide(alice(), id, pending.token(), true))
                .isInstanceOf(ApprovalDecision.Approved.class);

        assertThat(drive(id)).isEqualTo(IntentState.HANDED_OFF);
        assertThat(intent(id).handoffUrl().orElseThrow().value().toString()).isEqualTo(fixtureLink());
        assertThat(intent(id).handoffWatch()).isEqualTo(HandoffWatch.NONE);
        noFullLinkIn(id);
        noCheckoutCallSent();
    }

    // Covers: I12 C3, 26.7 (a link on seller.domain itself is accepted too)
    @Test
    void aLinkOnTheSellersDomain_isAccepted() {
        catalog.store.answerText("get_product",
                withCheckoutUrl(fixtureLink().replace("www.shop-a.example", "shop-a.myshopify.example")));
        wire();
        IntentId id = orchestrator.propose(alice(), GlobalCatalogGatewayTest.CATALOG, ONE, "a board").intentId();

        assertThat(drive(id)).isEqualTo(IntentState.AWAITING_APPROVAL);
    }

    // Covers: 7.2 link_refused link_not_allowed, I12 C3, I8, 6.2, 26.7 (owner's C3: an offer derived from the fixture
    // whose checkout_url host is neither seller.url's host nor seller.domain is refused: no approval opens, so no card
    // is ever shown; no handoff_url; the event names the host only; the propose result says why; nothing is sent)
    @Test
    void aLinkOffTheSellersHosts_isRefused_andNeverShown() {
        catalog.store.answerText("get_product",
                withCheckoutUrl(fixtureLink().replace("www.shop-a.example", "www.elsewhere.example")));
        wire();
        IntentId id = orchestrator.propose(alice(), GlobalCatalogGatewayTest.CATALOG, ONE, "a board").intentId();

        assertThat(drive(id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(intent(id).failureReason()).contains("link_not_allowed");
        assertThat(approvalService.pending(alice(), id)).as("no card").isEmpty();
        assertThat(approvals.listForSystem(id)).isEmpty();
        assertThat(intent(id).handoffUrl()).isEmpty();
        assertThat(intents.events(id)).filteredOn(e -> e.type().equals("link_refused"))
                .extracting(IntentEvent::payloadJson)
                .containsExactly("{\"host\":\"www.elsewhere.example\",\"reason\":\"link_not_allowed\"}");
        noFullLinkIn(id);
        noCheckoutCallSent();
    }

    // Covers: I12 C3, 26.7 (look-alikes are refused: a subdomain of the site, user info, another port, plain http)
    @Test
    void lookAlikeLinks_areRefused() {
        List<String> lookAlikes = List.of(
                fixtureLink().replace("www.shop-a.example", "www.shop-a.example.elsewhere.example"),
                fixtureLink().replace("www.shop-a.example", "pay.www.shop-a.example"),
                fixtureLink().replace("https://www.shop-a.example", "https://www.shop-a.example@elsewhere.example"),
                fixtureLink().replace("www.shop-a.example", "www.shop-a.example:8443"),
                fixtureLink().replace("https://", "http://"));
        for (String link : lookAlikes) {
            catalog.store.answerText("get_product", withCheckoutUrl(link));
        }
        wire();
        int n = 0;
        for (String link : lookAlikes) {
            IntentId id = orchestrator.propose(alice(), GlobalCatalogGatewayTest.CATALOG, ONE, "a board",
                    Optional.of(new Budget(50_000 + n++, "USD"))).intentId();
            assertThat(drive(id)).as(link).isEqualTo(IntentState.FAILED_NOT_CHARGED);
            assertThat(intent(id).failureReason()).as(link).contains("link_not_allowed");
            assertThat(approvals.listForSystem(id)).as(link).isEmpty();
        }
    }
}
