package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.approval.ApprovalSecret;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.ApprovalTokens;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.approval.FulfillmentSnapshot;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StaleVersionException;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.FulfillmentView;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.SessionId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of {@code DefaultApprovalService} that the H3 P5 mutation pass found unchecked (I3, I10, 9.2, 23.2): what the
 * events, the stored approvals and the snapshot say, as an operator reads them. Lives here to share {@link Lab}.
 */
class DefaultApprovalServiceMutationTest {

    private static final ApprovalSecret SECRET = ApprovalSecret.of("0123456789abcdef0123456789abcdef");

    private final Lab lab = new Lab();
    private final Lab.Worker w = lab.worker("worker-a");
    private final IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

    private List<IntentEvent> events(String type) {
        return lab.events(id).stream().filter(e -> e.type().equals(type)).toList();
    }

    private CheckoutView quote() {
        return lab.merchant.session(lab.intent(id).sessionId().orElseThrow()).orElseThrow();
    }

    /** A quote with two destinations in different countries and two options, the cheaper one (second) selected. */
    private static CheckoutView shippedQuote(Optional<String> selectedOption, Optional<String> selectedDestination) {
        FulfillmentView.Method method = new FulfillmentView.Method("method_1", "shipping", List.of("li_1"),
                List.of(new FulfillmentView.Destination("addr_1", Optional.of("US")),
                        new FulfillmentView.Destination("addr_2", Optional.of("CA"))),
                selectedDestination,
                List.of(new FulfillmentView.Group("g1", List.of("li_1"),
                        List.of(new FulfillmentView.Option("std", "Standard", 100),
                                new FulfillmentView.Option("exp", "Express", 50)), selectedOption)));
        return new CheckoutView(new SessionId("cs_x"), CheckoutStatus.READY_FOR_COMPLETE, "USD",
                List.of(new LineView("item_123", 2, 2500, Optional.of("li_1"))), 5000, 0, 5050, Optional.empty(),
                Optional.empty(), List.of(), Optional.of(new FulfillmentView(List.of(method))), 50, true);
    }

    // ---------------------------------------------------------------- open

    // Covers: I3, 7.3, 9.2. Kills DefaultApprovalService.open:81 RemoveConditional_EQUAL_ELSE (a second open leaves the
    // first PENDING approval PENDING, so two approvals could be approved) and open:101 NullReturnVals (open returns the
    // snapshot, its hash and a token): an open after a crash replaces the pending approval, and leaves a decided one alone
    @Test
    void open_again_expiresThePendingApproval_andLeavesDecidedOnesAlone() {
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        lab.merchant.setPrice("item_123", 2600);
        assertThat(lab.drive(w, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        List<ApprovalRecord> before = lab.approvals.listForSystem(id);
        assertThat(before).extracting(ApprovalRecord::status)
                .containsExactly(ApprovalStatus.CONSUMED, ApprovalStatus.PENDING);

        PendingApproval reopened = w.approvalService().open(lab.intent(id), quote());

        assertThat(lab.approvals.listForSystem(id)).extracting(ApprovalRecord::status)
                .containsExactly(ApprovalStatus.CONSUMED, ApprovalStatus.EXPIRED, ApprovalStatus.PENDING);
        assertThat(reopened.snapshot().totalMinor()).isEqualTo(5616);
        assertThat(reopened.snapshotHash()).isEqualTo(reopened.snapshot().hash());
        assertThat(w.approvalService().decide(lab.alice(), id, reopened.token(), true))
                .isInstanceOf(ApprovalDecision.Approved.class);
    }

    // Covers: 9.2, 23.2, 11. Kills open:100 VoidMethodCall (the approval_opened event is appended): the event lists the
    // approval id, the snapshot hash and the total and the maximum total in minor units
    @Test
    void open_recordsAnApprovalOpenedEvent_withTheSnapshotHashAndTotals() {
        PendingApproval p = lab.pending(w, id);
        ApprovalRecord record = lab.approvals.findLatestForSystem(id).orElseThrow();

        assertThat(events("approval_opened")).singleElement().satisfies(e -> assertThat(e.payloadJson())
                .contains("\"approval_id\":\"" + record.id() + "\"")
                .contains("\"snapshot_hash\":\"" + p.snapshotHash() + "\"")
                .contains("\"total_minor\":5400").contains("\"max_total_minor\":5400"));
    }

    // Covers: 9.2, I2, D4. Kills DefaultApprovalService.lambda$shipping$1:108 and lambda$shipping$4:111 BooleanTrueReturnVals
    // (the snapshot names the option and the destination the merchant selected, not the first it listed): the second
    // option, in the second destination's country
    @Test
    void open_snapshotsTheSelectedOptionAndTheSelectedDestinationsCountry() {
        PendingApproval p = w.approvalService().open(lab.intent(id), shippedQuote(Optional.of("exp"), Optional.of("addr_2")));

        assertThat(p.snapshot().fulfillment()).contains(new FulfillmentSnapshot("exp", "Express", 50, Optional.of("CA")));
    }

    // ---------------------------------------------------------------- pending

    // Covers: 23.2, 9.2. Kills DefaultApprovalService.lambda$pending$10:127 RemoveConditional_EQUAL_IF (a decided approval
    // is not pending any more: the approver channel shows nothing to decide after the decision)
    @Test
    void pending_isEmptyOnceTheApprovalIsDecided() {
        assertThat(w.approvalService().pending(lab.alice(), id)).isPresent();

        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);

        assertThat(w.approvalService().pending(lab.alice(), id)).isEmpty();
    }

    // Covers: I10, 18, T6. Kills DefaultApprovalService.pending:122 RemoveConditional_EQUAL_ELSE (the owner check before the
    // approval lookup, the second line of defence for I10): even when the approval store does not scope its read by user, a
    // user who does not own the intent is shown nothing to decide, and the owner still is
    @Test
    void pending_asANonOwner_isEmpty_evenWhenTheApprovalStoreIgnoresTheUser() {
        ApprovalRepository ignoringTheUser = (ApprovalRepository) Proxy.newProxyInstance(
                ApprovalRepository.class.getClassLoader(), new Class<?>[] {ApprovalRepository.class}, (proxy, method, args) -> {
                    try {
                        if (method.getName().equals("findLatest")) {
                            return lab.approvals.findLatestForSystem((IntentId) args[1]);
                        }
                        return method.invoke(lab.approvals, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        Lab.Worker unscoped = lab.worker("worker-b", lab.merchant, ignoringTheUser);

        assertThat(unscoped.approvalService().pending(lab.bob(), id)).isEmpty();
        assertThat(unscoped.approvalService().pending(lab.alice(), id)).isPresent();
    }

    // ---------------------------------------------------------------- decide

    // Covers: I3, 7.2. Kills DefaultApprovalService.decide:144 RemoveConditional (no approval at all): an intent awaiting
    // approval that has no approval row is not pending, and nothing changes
    @Test
    void decide_withNoApprovalAtAll_isNotPending() {
        IntentId bare = new IntentId("bare");
        lab.intents.insert(PurchaseIntent.proposed(bare, Lab.ALICE, Lab.SHOP, "bare-key", TWO_SHIRTS, "alice@example.com",
                lab.clock.instant()).withState(IntentState.AWAITING_APPROVAL, lab.clock.instant()));
        PurchaseIntent before = lab.intent(bare);

        ApprovalDecision d = w.approvalService().decide(lab.alice(), bare, "any-token", true);

        assertThat(d).isInstanceOf(ApprovalDecision.NotPending.class);
        assertThat(lab.intent(bare)).isEqualTo(before);
    }

    // Covers: I3, 9.2. Kills DefaultApprovalService.decide:144 RemoveConditional (the newest approval is not PENDING): an
    // approval that was rejected or expired cannot be approved with its own valid token, even if the intent still waits
    @Test
    void decide_onANonPendingApproval_isNotPending_andApprovesNothing() {
        PendingApproval p = lab.pending(w, id);
        ApprovalRecord record = lab.approvals.findLatestForSystem(id).orElseThrow();
        assertThat(lab.approvals.transition(record.id(), ApprovalStatus.PENDING, ApprovalStatus.EXPIRED, lab.clock.instant(),
                Optional.empty())).isTrue();
        PurchaseIntent before = lab.intent(id);

        ApprovalDecision d = w.approvalService().decide(lab.alice(), id, p.token(), true);

        assertThat(d).isInstanceOf(ApprovalDecision.NotPending.class);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(lab.intent(id).approvedSnapshotHash()).isEqualTo(before.approvedSnapshotHash());
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.EXPIRED);
    }

    // Covers: I3, 7.2. Kills DefaultApprovalService.decide:166 RemoveConditional_EQUAL_ELSE / EQUAL_IF and decide:167
    // NullReturnVals: a valid token on a PENDING approval does not approve an intent that is no longer awaiting approval
    @Test
    void decide_onAnIntentThatIsNotAwaitingApproval_isNotPending() {
        PendingApproval p = lab.pending(w, id);
        lab.clock.advance(lab.config.lease().plusSeconds(1));    // worker-a's lease from the propose has run out
        PurchaseIntent claimed = lab.intents.claim(id, "other", lab.config.lease()).orElseThrow();
        lab.intents.update(claimed.withState(IntentState.QUOTING, lab.clock.instant()), claimed.version(), "other");
        lab.clock.advance(lab.config.lease().plusSeconds(1));

        ApprovalDecision d = w.approvalService().decide(lab.alice(), id, p.token(), true);

        assertThat(d).isInstanceOf(ApprovalDecision.NotPending.class);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.QUOTING);
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(events("approval_decided")).isEmpty();
    }

    // Covers: I3, 7.8, 23.5. Kills DefaultApprovalService.decide:179 NullReturnVals (NO_COVERAGE): when the intent write
    // loses a race (StaleVersionException) the decision is NotPending, the approval stays PENDING and no decision event
    // is recorded
    @Test
    void decide_whenTheIntentWriteLosesARace_isNotPending_andRecordsNothing() {
        IntentRepository racing = (IntentRepository) Proxy.newProxyInstance(IntentRepository.class.getClassLoader(),
                new Class<?>[] {IntentRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("update")) {
                        throw new StaleVersionException(id, 0, 1);
                    }
                    try {
                        return method.invoke(lab.intents, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        Lab.Worker loser = lab.worker("worker-b", lab.merchant, lab.approvals, racing);
        PendingApproval p = lab.pending(w, id);
        lab.clock.advance(lab.config.lease().plusSeconds(1));    // worker-a's lease from the propose has run out

        ApprovalDecision d = loser.approvalService().decide(lab.alice(), id, p.token(), true);

        assertThat(d).isInstanceOf(ApprovalDecision.NotPending.class);
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(events("approval_decided")).isEmpty();
    }

    // Covers: I10, 9.2, 11. Kills DefaultApprovalService.decide:173 RemoveConditional (approve sets the approved snapshot
    // hash and no failure reason), decide:183 RemoveConditional (the approval becomes APPROVED, decided_by Alice) and
    // decide:187 RemoveConditional / decide:189 VoidMethodCall (the approval_decided event says "approve" and who)
    @Test
    void approve_recordsTheDecision_inTheApprovalTheIntentAndTheEvents() {
        PendingApproval p = lab.pending(w, id);

        assertThat(w.approvalService().decide(lab.alice(), id, p.token(), true)).isInstanceOf(ApprovalDecision.Approved.class);

        PurchaseIntent intent = lab.intent(id);
        assertThat(intent.state()).isEqualTo(IntentState.APPROVED);
        assertThat(intent.approvedSnapshotHash()).contains(p.snapshotHash());
        assertThat(intent.failureReason()).isEmpty();
        ApprovalRecord record = lab.approvals.findLatestForSystem(id).orElseThrow();
        assertThat(record.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(record.decidedBy()).contains(Lab.ALICE);
        assertThat(events("approval_decided")).singleElement().satisfies(e -> assertThat(e.payloadJson())
                .contains("\"approval_id\":\"" + record.id() + "\"").contains("\"decision\":\"approve\"")
                .contains("\"decided_by\":\"alice\""));
    }

    // Covers: I10, 9.2, 11, 7.2. Kills DefaultApprovalService.decide:173, decide:183 and decide:187 (the reject side): a
    // rejection ends the intent REJECTED with the reason approval_rejected and no approved hash, the approval REJECTED with
    // decided_by, and the event says "reject"
    @Test
    void reject_recordsTheDecision_inTheApprovalTheIntentAndTheEvents() {
        PendingApproval p = lab.pending(w, id);

        assertThat(w.approvalService().decide(lab.alice(), id, p.token(), false)).isInstanceOf(ApprovalDecision.Rejected.class);

        PurchaseIntent intent = lab.intent(id);
        assertThat(intent.state()).isEqualTo(IntentState.REJECTED);
        assertThat(intent.failureReason()).contains(DefaultApprovalService.APPROVAL_REJECTED);
        assertThat(intent.approvedSnapshotHash()).isEmpty();
        ApprovalRecord record = lab.approvals.findLatestForSystem(id).orElseThrow();
        assertThat(record.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(record.decidedBy()).contains(Lab.ALICE);
        assertThat(events("approval_decided")).singleElement().satisfies(e -> assertThat(e.payloadJson())
                .contains("\"decision\":\"reject\"").contains("\"decided_by\":\"alice\""));
        assertThat(events("transition")).anySatisfy(e -> assertThat(e.payloadJson()).contains("\"to\":\"REJECTED\""));
    }

    // Covers: 23.5, 21.3. Kills DefaultApprovalService.lambda$decide$13:161 BooleanTrueReturnVals / RemoveConditional (the
    // time left on a held lease is never negative: instances whose clocks disagree still say "retry after 0", not a
    // negative wait). The intent is held by worker-a until T0+lease on the store's clock; this service's clock is minutes
    // ahead
    @Test
    void decide_onAHeldIntent_isBusyWithNeverANegativeWait_whenClocksDisagree() {
        PendingApproval p = lab.pending(w, id);
        assertThat(lab.intents.claim(id, "worker-a", lab.config.lease())).isPresent();
        Instant ahead = lab.clock.instant().plus(Duration.ofMinutes(5));
        DefaultApprovalService skewed = new DefaultApprovalService(lab.intents, lab.approvals,
                new ApprovalTokens(SECRET), new MutableClock(ahead), lab.ids, "worker-b", lab.config.lease(),
                DefaultApprovalService.DEFAULT_TTL, DefaultApprovalService.DEFAULT_MAX_AUTH_AGE);
        AuthContext fresh = new AuthContext(Lab.ALICE, "alice@example.com", ahead, "session-alice");

        ApprovalDecision d = skewed.decide(fresh, id, p.token(), true);

        assertThat(d).isEqualTo(new ApprovalDecision.Busy(Duration.ZERO));
    }
}
