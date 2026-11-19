package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.LinkSnapshot;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.approval.SnapshotLine;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.HandoffWatch;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.ContinueUrl;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.LinkOffer;
import dev.ucplab.core.merchant.LinkOffers;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Money;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.Seller;
import dev.ucplab.core.merchant.Transport;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The link rows of table 7.2 (real catalog stage 2; 26.7, I12, I2, I3, I6, I8, P9, 20) at a catalog source whose
 * {@code completion} is {@code link}. The offer comes from a {@link LinkOffers} double; the merchant gateway counts any
 * call, and none may come: nothing is sent to a seller.
 */
class LinkHandoffTest {

    static final MerchantEntry CATALOG = new MerchantEntry(SHOP, URI.create("https://catalog.shopify.com/api/ucp/mcp"),
            "Shopify catalog", MerchantStatus.ACTIVE, Transport.CATALOG, Completion.LINK, false, Optional.empty());
    static final String ITEM = "gid://shopify/ProductVariant/1000001";
    static final List<ProposedLine> ONE = List.of(new ProposedLine(ITEM, 1));
    static final String LINK = "https://www.shop-a.example/buy/1000001:1?_gsid=FAKEGSID0001"
            + "&attribution%2Fclick_id_value=FAKECLICK0001&attribution%2Fclick_id_tag=FAKETAG";
    static final Seller SELLER = new Seller("Example Shop A", "shop-a.myshopify.example",
            Optional.of("www.shop-a.example"));

    static LinkOffer offer(String link) {
        return new LinkOffer(ITEM, "Example Snowboard 1", new Money(12_900, "USD"), true, SELLER,
                Optional.of(new ContinueUrl(URI.create(link))));
    }

    /** The catalog's offer reads: each answered by {@link #answer}, each recorded. */
    static final class Offers implements LinkOffers {
        final List<String> reads = new ArrayList<>();
        UnaryOperator<CallOutcome<LinkOffer>> change = o -> o;
        CallOutcome<LinkOffer> answer = new CallOutcome.Ok<>(200, LinkHandoffTest.offer(LINK), List.of());

        @Override
        public CallOutcome<LinkOffer> offer(MerchantId merchant, String itemId) {
            reads.add(merchant.value() + " " + itemId);
            return change.apply(answer);
        }
    }

    final Lab lab = new Lab();
    final Offers offers = new Offers();
    final List<String> gatewayCalls = new ArrayList<>();
    final MerchantGateway counting = (MerchantGateway) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {MerchantGateway.class}, (proxy, method, args) -> {
                if (method.getName().equals("admitsCalls")) {
                    return true;
                }
                gatewayCalls.add(method.getName());
                throw new AssertionError("a link intent sent " + method.getName() + " to a merchant gateway");
            });

    LinkHandoffTest() {
        lab.entries = id -> id.equals(SHOP) ? Optional.of(CATALOG) : Optional.empty();
        lab.links = offers;
    }

    Lab.Worker worker() {
        return lab.worker("w", counting);
    }

    private List<String> events(IntentId id, String type) {
        return lab.events(id).stream().filter(e -> e.type().equals(type)).map(IntentEvent::payloadJson).toList();
    }

    private void nothingSent(IntentId id) {
        assertThat(gatewayCalls).as("calls to a merchant gateway").isEmpty();
        assertThat(lab.intents.attempts(id)).as("attempt rows").isEmpty();
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(lab.intent(id).sessionId()).isEmpty();
    }

    private void noFullLinkAnywhere(IntentId id) {
        for (IntentEvent e : lab.events(id)) {
            assertThat(e.payloadJson()).as(e.type()).doesNotContain("_gsid").doesNotContain("FAKECLICK")
                    .doesNotContain("/buy/");
        }
    }

    private IntentId quoted(Lab.Worker w) {
        IntentId id = w.orchestrator().propose(lab.alice(), SHOP, ONE, "a board").intentId();
        assertThat(lab.drive(w, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        return id;
    }

    private IntentState failedWith(Lab.Worker w, List<ProposedLine> lines, String reason) {
        IntentId id = w.orchestrator().propose(lab.alice(), SHOP, lines, "a board").intentId();
        IntentState end = lab.drive(w, id);
        assertThat(lab.intent(id).failureReason()).contains(reason);
        assertThat(events(id, "transition")).as("the transition into " + end)
                .anySatisfy(e -> assertThat(e).contains("\"to\":\"" + end + "\""));
        assertThat(lab.approvals.listForSystem(id)).as("no approval, so no card").isEmpty();
        nothingSent(id);
        noFullLinkAnywhere(id);
        return end;
    }

    // ---------------------------------------------------------------- quote_ready (link)

    // Covers: 7.2 quote_ready (link), I2, 9.2, 20, 26.7, I12 (propose accepts the catalog without owned; the quote is
    // one fresh read of the offer; the snapshot holds the catalog's title, price and currency and the link exactly as
    // read; no attempt row, nothing sent to any gateway)
    @Test
    void quoteReady_readsTheOfferOnce_andOpensTheApprovalOnIt() {
        Lab.Worker w = worker();
        IntentId id = quoted(w);

        assertThat(offers.reads).containsExactly("shop-1 " + ITEM);
        PendingApproval pending = lab.pending(w, id);
        assertThat(pending.snapshot().lines()).containsExactly(new SnapshotLine(ITEM, 1, 12_900, "Example Snowboard 1"));
        assertThat(pending.snapshot().currency()).isEqualTo("USD");
        assertThat(pending.snapshot().totalMinor()).isEqualTo(12_900);
        assertThat(pending.snapshot().sessionId().value()).isEqualTo("link:" + ITEM);
        assertThat(pending.snapshot().link()).contains(new LinkSnapshot("Example Shop A", "shop-a.myshopify.example",
                Optional.of("www.shop-a.example"), new ContinueUrl(URI.create(LINK))));
        assertThat(events(id, "transition")).contains(
                "{\"event\":\"QUOTE_READY\",\"from\":\"QUOTING\",\"guard\":\"QUOTE_POLICY_ALLOWS\",\"to\":\"AWAITING_APPROVAL\"}");
        assertThat(events(id, "policy_decision")).filteredOn(e -> e.contains("\"stage\":\"quote\"")).singleElement()
                .satisfies(e -> assertThat(e).contains("\"allowed\":true"));
        nothingSent(id);
        noFullLinkAnywhere(id);
    }

    // Covers: 20, 26.7 (the read that prices an approval is never cached: each quote reads the catalog again)
    @Test
    void eachQuote_readsTheCatalogAgain() {
        Lab.Worker w = worker();
        quoted(w);
        IntentId second = w.orchestrator().propose(lab.alice(), SHOP, List.of(new ProposedLine(ITEM, 1)), "again",
                Optional.of(new Budget(50_000, "USD"))).intentId();
        assertThat(lab.drive(w, second)).isEqualTo(IntentState.AWAITING_APPROVAL);

        assertThat(offers.reads).hasSize(2);
    }

    // ---------------------------------------------------------------- hand_off (link)

    // Covers: 7.2 hand_off (link), I3, I8, 26.7, 7.6 (approval hands off the approved link exactly, with no follow-up
    // and no new read; the event names the host only; the approval is consumed; nothing is sent)
    @Test
    void approve_handsOffTheApprovedLink_withNoFollowUp() {
        Lab.Worker w = worker();
        IntentId id = quoted(w);
        lab.decide(w, id, true);

        assertThat(lab.drive(w, id)).isEqualTo(IntentState.HANDED_OFF);

        assertThat(lab.intent(id).handoffUrl()).contains(new ContinueUrl(URI.create(LINK)));
        assertThat(lab.intent(id).handoffUrl().orElseThrow().value().toString()).isEqualTo(LINK);
        assertThat(lab.intent(id).handoffWatch()).isEqualTo(HandoffWatch.NONE);
        assertThat(events(id, "handed_off")).containsExactly("{\"host\":\"www.shop-a.example\"}");
        assertThat(events(id, "transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"HAND_OFF\"")
                .contains("\"from\":\"APPROVED\"").contains("\"to\":\"HANDED_OFF\""));
        assertThat(events(id, "approval_decision_recovered")).as("an approval already APPROVED is not recovered")
                .isEmpty();
        assertThat(lab.approvals.findLatestForSystem(id)).map(ApprovalRecord::status).contains(ApprovalStatus.CONSUMED);
        assertThat(offers.reads).hasSize(1);
        nothingSent(id);
        noFullLinkAnywhere(id);
        lab.clock.advance(Duration.ofHours(1));
        assertThat(w.orchestrator().advance(id).idle()).isTrue();
        assertThat(gatewayCalls).isEmpty();
    }

    // Covers: 7.2 (a rejected link approval ends REJECTED with nothing to cancel and nothing sent)
    @Test
    void reject_endsRejected_withNothingToCancel() {
        Lab.Worker w = worker();
        IntentId id = quoted(w);
        lab.decide(w, id, false);

        assertThat(lab.drive(w, id)).isEqualTo(IntentState.REJECTED);
        assertThat(lab.intent(id).handoffUrl()).isEmpty();
        nothingSent(id);
    }

    // ---------------------------------------------------------------- failures

    // Covers: 7.2 link_refused, 26.7 C1 (not one line of quantity 1: link_quantity_unsupported, before any read)
    @Test
    void notOneLineOfQuantityOne_isLinkQuantityUnsupported_withoutARead() {
        assertThat(failedWith(worker(), List.of(new ProposedLine(ITEM, 2)), "link_quantity_unsupported"))
                .isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(failedWith(worker(), List.of(new ProposedLine(ITEM, 1),
                new ProposedLine("gid://shopify/ProductVariant/1000002", 1)), "link_quantity_unsupported"))
                .isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(offers.reads).isEmpty();
    }

    // Covers: 7.2 item_unavailable (link) (not available, or refused by the catalog)
    @Test
    void anUnavailableOrRefusedItem_isItemUnavailable() {
        offers.change = o -> new CallOutcome.Ok<>(200, new LinkOffer(ITEM, "Example Snowboard 1",
                new Money(12_900, "USD"), false, SELLER, Optional.of(new ContinueUrl(URI.create(LINK)))), List.of());
        assertThat(failedWith(worker(), ONE, "item_unavailable")).isEqualTo(IntentState.FAILED_NOT_CHARGED);

        offers.change = o -> new CallOutcome.Rejected<>(200, List.of(new MerchantMessage("error", "not_found",
                Optional.empty(), "x", Optional.empty())));
        assertThat(failedWith(worker(), List.of(new ProposedLine(ITEM, 1)), "item_unavailable"))
                .isEqualTo(IntentState.FAILED_NOT_CHARGED);
    }

    // Covers: 7.2 retries exhausted (link), link_refused catalog_unreadable (a read that never settled is
    // quote_retries_exhausted; an unreadable answer is catalog_unreadable)
    @Test
    void aFailedRead_isRetriesExhausted_andAnUnreadableOne_catalogUnreadable() {
        List<CallOutcome<LinkOffer>> unsettled = List.of(new CallOutcome.NotSent<>("breaker open"),
                new CallOutcome.Uncertain<>("timeout"),
                new CallOutcome.ProtocolError<>(429, "rate_limited", "", Optional.of(Duration.ofSeconds(1))),
                new CallOutcome.ProtocolError<>(503, "unavailable", "", Optional.empty()));
        int n = 0;
        for (CallOutcome<LinkOffer> outcome : unsettled) {
            offers.change = o -> outcome;
            List<ProposedLine> distinct = List.of(new ProposedLine("gid://shopify/ProductVariant/200000" + n++, 1));
            assertThat(failedWith(worker(), distinct, "quote_retries_exhausted")).as(outcome.toString())
                    .isEqualTo(IntentState.FAILED_NOT_CHARGED);
        }
        offers.change = o -> new CallOutcome.ProtocolError<>(200, "invalid_response", "ucp.version", Optional.empty());
        assertThat(failedWith(worker(), ONE, "catalog_unreadable")).isEqualTo(IntentState.FAILED_NOT_CHARGED);
    }

    // Covers: 7.2 link_refused link_not_allowed, I12, 26.7 C3, I8 (a link whose host is neither the seller's site nor
    // its domain is refused: no approval opens, so no card is shown; the event names its host only; a missing or
    // plain-http link is refused too; a link on seller.domain is accepted)
    @Test
    void aLinkOffTheSellersHosts_isRefused_andNeverShown() {
        offers.change = o -> new CallOutcome.Ok<>(200,
                offer(LINK.replace("www.shop-a.example", "www.elsewhere.example")), List.of());
        Lab.Worker w = worker();
        IntentId id = w.orchestrator().propose(lab.alice(), SHOP, ONE, "a board").intentId();
        assertThat(lab.drive(w, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("link_not_allowed");
        assertThat(lab.approvals.listForSystem(id)).isEmpty();
        assertThat(lab.intent(id).handoffUrl()).isEmpty();
        assertThat(events(id, "link_refused"))
                .containsExactly("{\"host\":\"www.elsewhere.example\",\"reason\":\"link_not_allowed\"}");
        nothingSent(id);
        noFullLinkAnywhere(id);

        offers.change = o -> new CallOutcome.Ok<>(200, new LinkOffer(ITEM, "Example Snowboard 1",
                new Money(12_900, "USD"), true, SELLER, Optional.empty()), List.of());
        assertThat(failedWith(worker(), List.of(new ProposedLine(ITEM, 1)), "link_not_allowed"))
                .isEqualTo(IntentState.FAILED_NOT_CHARGED);
    }

    // Covers: 9.1 P9, 7.2 (a listed price over the run's budget is denied: no approval, failure policy_P9, and the
    // refused quote goes back with the result)
    @Test
    void aPriceOverTheBudget_isDeniedByP9() {
        Lab.Worker w = worker();
        ProposalResult r = w.orchestrator().propose(lab.alice(), SHOP, ONE, "a board",
                Optional.of(new Budget(10_000, "USD")));
        IntentId id = r.intentId();
        lab.drive(w, id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("policy_P9");
        assertThat(events(id, "policy_decision")).filteredOn(e -> e.contains("\"stage\":\"quote\"")).singleElement()
                .satisfies(e -> assertThat(e).contains("\"allowed\":false").contains("\"rule\":\"P9\""));
        assertThat(lab.approvals.listForSystem(id)).isEmpty();
        nothingSent(id);
    }

    // ---------------------------------------------------------------- the paths the mutation pass found untested

    /** A link intent's quote as the orchestrator builds it, under {@code session}. */
    private static CheckoutView view(String session) {
        return new CheckoutView(new SessionId(session), CheckoutStatus.READY_FOR_COMPLETE, "USD",
                List.of(new LineView(ITEM, 1, 12_900, Optional.empty(), "Example Snowboard 1")), 12_900, 0, 12_900,
                Optional.empty(), List.of());
    }

    private static LinkSnapshot snapshotLink(String link) {
        return new LinkSnapshot(SELLER.name(), SELLER.domain(), SELLER.site(), new ContinueUrl(URI.create(link)));
    }

    /** Replaces the intent's pending approval with one opened on {@code quote} and {@code link}, and approves it. */
    private void approveInstead(Lab.Worker w, IntentId id, CheckoutView quote, Optional<LinkSnapshot> link) {
        PendingApproval replaced = link.isPresent()
                ? w.approvalService().openLink(lab.intent(id), quote, link.get())
                : w.approvalService().open(lab.intent(id), quote);
        assertThat(replaced.snapshot().link()).isEqualTo(link);
        w.approvalService().decide(lab.alice(), id, replaced.token(), true);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
    }

    private void handOffRefused(Lab.Worker w, IntentId id, String expectedEvent) {
        assertThat(lab.drive(w, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("continue_url_not_allowed");
        assertThat(lab.intent(id).handoffUrl()).isEmpty();
        assertThat(events(id, "hand_off_refused")).containsExactly(expectedEvent);
        assertThat(events(id, "transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"HAND_OFF_REFUSED\""));
        nothingSent(id);
        noFullLinkAnywhere(id);
    }

    // Covers: 7.2 hand_off (link), I12 C3, I8 (a snapshot link that fails I12's link rule at hand-off takes
    // hand_off_refused, with nothing to cancel: no handoff_url, the event names its host only, nothing is sent)
    @Test
    void anApprovedLinkThatFailsTheRule_atHandOff_isRefused_withNothingToCancel() {
        Lab.Worker w = worker();
        IntentId id = quoted(w);
        approveInstead(w, id, view("link:" + ITEM),
                Optional.of(snapshotLink(LINK.replace("www.shop-a.example", "www.elsewhere.example"))));

        handOffRefused(w, id, "{\"host\":\"www.elsewhere.example\"}");
    }

    // Covers: 7.2 hand_off (link), I12 (an approved snapshot without a link is refused at hand-off: there is no link to
    // check, and the event has no host)
    @Test
    void anApprovedSnapshotWithoutALink_atHandOff_isRefused() {
        Lab.Worker w = worker();
        IntentId id = quoted(w);
        approveInstead(w, id, view("link:" + ITEM), Optional.empty());

        handOffRefused(w, id, "{}");
    }

    // Covers: 7.2 approval_invalid, I3, 9.2 (at a link merchant I3 compares the snapshot's sessionId with
    // link:<the line's item id>: an approval for another item escalates and hands nothing off)
    @Test
    void anApprovalForAnotherItem_isApprovalInvalid() {
        Lab.Worker w = worker();
        IntentId id = quoted(w);
        approveInstead(w, id, view("link:gid://shopify/ProductVariant/9999999"), Optional.of(snapshotLink(LINK)));

        assertThat(lab.drive(w, id)).isEqualTo(IntentState.ESCALATED);
        assertThat(lab.intent(id).failureReason()).contains("approval_invalid");
        assertThat(lab.intent(id).handoffUrl()).isEmpty();
        nothingSent(id);
    }

    // Covers: 7.2 approval_invalid, I3, 9.2, 26.7 C1 (I3 at a link merchant holds only for one line: an intent of two
    // lines, whose first matches the snapshot's session, escalates and hands nothing off)
    @Test
    void anIntentOfTwoLines_isApprovalInvalid_evenWhenItsFirstLineMatches() {
        Lab.Worker w = worker();
        Instant now = lab.clock.instant();
        IntentId id = new IntentId("i-two-lines");
        lab.intents.insert(PurchaseIntent.proposed(id, Lab.ALICE, SHOP, CATALOG.fingerprint(), "dedupe-two",
                List.of(new ProposedLine(ITEM, 1), new ProposedLine("gid://shopify/ProductVariant/1000002", 1)),
                "alice@example.com", now).withState(IntentState.AWAITING_APPROVAL, now));
        approveInstead(w, id, view("link:" + ITEM), Optional.of(snapshotLink(LINK)));

        assertThat(lab.drive(w, id)).isEqualTo(IntentState.ESCALATED);
        assertThat(lab.intent(id).failureReason()).contains("approval_invalid");
        assertThat(lab.intent(id).handoffUrl()).isEmpty();
        nothingSent(id);
    }

    // Covers: 7.2 hand_off (link) (send_complete's order: an approval left PENDING by a crash inside decide is
    // recovered to APPROVED, as the owner's, then consumed), I10
    @Test
    void aPendingApprovalUnderAnApprovedIntent_isRecovered_thenConsumed_atHandOff() {
        CrashingApprovalRepository crashing = new CrashingApprovalRepository(lab.approvals);
        Lab.Worker dying = lab.worker("worker-x", counting, crashing);
        IntentId id = quoted(dying);
        crashing.crashBeforeNext(CrashingApprovalRepository.Op.TRANSITION);
        assertThatThrownBy(() -> lab.decide(dying, id, true)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        lab.clock.advance(lab.config.lease().plusSeconds(1));

        assertThat(lab.drive(lab.worker("worker-y", counting), id)).isEqualTo(IntentState.HANDED_OFF);

        ApprovalRecord approval = lab.approvals.findLatestForSystem(id).orElseThrow();
        assertThat(approval.status()).isEqualTo(ApprovalStatus.CONSUMED);
        assertThat(approval.decidedBy()).contains(Lab.ALICE);
        assertThat(events(id, "approval_decision_recovered")).singleElement()
                .satisfies(e -> assertThat(e).contains("\"approval_id\":\"" + approval.id() + "\""));
        assertThat(lab.intent(id).handoffUrl()).contains(new ContinueUrl(URI.create(LINK)));
        nothingSent(id);
    }

    // Covers: 7.2 item_unavailable (link) (the catalog answered for another variant than the one proposed)
    @Test
    void anOfferForAnotherVariant_isItemUnavailable() {
        offers.change = o -> new CallOutcome.Ok<>(200, new LinkOffer("gid://shopify/ProductVariant/1000002",
                "Example Snowboard 2", new Money(12_900, "USD"), true, SELLER,
                Optional.of(new ContinueUrl(URI.create(LINK)))), List.of());

        assertThat(failedWith(worker(), ONE, "item_unavailable")).isEqualTo(IntentState.FAILED_NOT_CHARGED);
    }

    // Covers: 7.2 link_refused link_not_allowed, I12 C3, I8 (a checkout_url with no host is refused, and the event
    // carries the reason alone)
    @Test
    void aLinkWithNoHost_isRefused_andTheEventHasTheReasonAlone() {
        offers.change = o -> new CallOutcome.Ok<>(200, offer("https:/buy/1000001:1"), List.of());
        Lab.Worker w = worker();
        IntentId id = w.orchestrator().propose(lab.alice(), SHOP, ONE, "a board").intentId();

        assertThat(lab.drive(w, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("link_not_allowed");
        assertThat(events(id, "link_refused")).containsExactly("{\"reason\":\"link_not_allowed\"}");
        nothingSent(id);
    }
}
