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
import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.core.orchestrator.OrchestratorConfig;
import dev.ucplab.core.policy.PolicyConfig;
import dev.ucplab.core.policy.PolicyUsage;
import dev.ucplab.core.policy.RequotePolicy;
import dev.ucplab.core.policy.RuleBasedPolicyEngine;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The orchestrator, the real {@link UcpHttpGateway} and the real merchant together, with every claim checked against
 * the merchant's /_lab/orders (13.1). It is in agent-ucp's tests because agent-core must not depend on agent-ucp.
 */
class EndToEndContractTest extends ContractTestBase {

    private static final UserId ALICE = new UserId("alice");
    private static final List<ProposedLine> TWO_SHIRTS = List.of(new ProposedLine("item_123", 2));

    // The test class has one instance for all its tests (it shares one merchant), so everything is built in wire().
    private TestClock clock;
    private InMemoryIntentRepository intents;
    private InMemoryApprovalRepository approvals;
    private DefaultOrchestrator orchestrator;
    private DefaultApprovalService approvalService;
    private UcpHttpGateway gateway;

    private void wire() {
        clock = new TestClock();
        intents = new InMemoryIntentRepository(clock);
        approvals = new InMemoryApprovalRepository();
        gateway = gateway();
        PolicyConfig policyConfig = PolicyConfig.defaults();
        RuleBasedPolicyEngine policy = new RuleBasedPolicyEngine(policyConfig,
                m -> m.equals(SHOP)
                        ? Optional.of(new MerchantEntry(SHOP, URI.create("http://127.0.0.1:8081"), "Shop",
                                dev.ucplab.core.merchant.MerchantStatus.ACTIVE))
                        : Optional.empty(),
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
        SequentialIdGenerator ids = new SequentialIdGenerator("e2e");
        OrchestratorConfig config = OrchestratorConfig.defaults();
        approvalService = new DefaultApprovalService(intents, approvals,
                new ApprovalTokens(ApprovalSecret.of("0123456789abcdef0123456789abcdef")), clock, ids, "worker-1",
                config.lease(), DefaultApprovalService.DEFAULT_TTL, DefaultApprovalService.DEFAULT_MAX_AUTH_AGE);
        orchestrator = new DefaultOrchestrator(intents, approvals, approvalService, gateway, policy,
                new RequotePolicy(policyConfig), PaymentInstrument.mockCard("pi_1", "tok_ok"), config, clock, ids,
                "worker-1", new SplittableRandom(42));
    }

    private AuthContext alice() {
        return new AuthContext(ALICE, "alice@example.com", clock.instant(), "session-alice");
    }

    private IntentId proposeAndApprove() {
        wire();
        ProposalResult proposed = orchestrator.propose(alice(), SHOP, TWO_SHIRTS, "two red t-shirts");
        assertThat(proposed.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        PendingApproval pending = approvalService.pending(alice(), proposed.intentId()).orElseThrow();
        assertThat(approvalService.decide(alice(), proposed.intentId(), pending.token(), true))
                .isInstanceOf(ApprovalDecision.Approved.class);
        return proposed.intentId();
    }

    /** Calls advance until idle. Nothing here needs a wait: a real merchant answers at once. */
    private IntentState drive(IntentId id) {
        for (int i = 0; i < 50; i++) {
            StepResult r = orchestrator.advance(id);
            if (r.idle()) {
                return r.after();
            }
        }
        throw new AssertionError("intent did not settle");
    }

    private PurchaseIntent intent(IntentId id) {
        return intents.findForSystem(id).orElseThrow();
    }

    // Covers: S0, G1, FR1 to FR5, I1, I2, I3 (propose, approve, complete: COMPLETED, and the merchant holds exactly one
    // order that matches the approved snapshot: same session, total within the approved maximum)
    @Test
    void s0_happyPath_endsCompletedWithExactlyOneMatchingOrder() {
        IntentId id = proposeAndApprove();

        assertThat(drive(id)).isEqualTo(IntentState.COMPLETED);

        var approval = approvals.findLatestForSystem(id).orElseThrow();
        assertThat(approval.snapshot().totalMinor()).isEqualTo(5400);
        assertThat(merchant.orders()).singleElement().satisfies(o -> {
            assertThat(o.get("id")).isEqualTo(intent(id).orderId().orElseThrow());
            assertThat(o.get("checkout_id")).isEqualTo(approval.snapshot().sessionId().value());
            assertThat((Long) o.get("amount")).isLessThanOrEqualTo(approval.snapshot().maxTotalMinor());
            assertThat(o.get("amount")).isEqualTo(approval.snapshot().totalMinor());
            assertThat(o.get("buyer_email")).isEqualTo("alice@example.com");
        });
    }

    // Covers: S6, I1 (the item sells out after the quote: the real merchant answers complete with ready_for_complete and
    // an out_of_stock error; the intent is FAILED_NOT_CHARGED, the session is cancelled at the merchant, no order exists
    // and no stock was taken)
    @Test
    void s6_outOfStockAtComplete_endsFailedNotCharged_withTheSessionCancelled() {
        IntentId id = proposeAndApprove();
        merchant.setStock("item_123", 1);

        assertThat(drive(id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);

        assertThat(intent(id).failureReason()).contains("out_of_stock");
        assertThat(merchant.orders()).isEmpty();
        CallOutcome<CheckoutView> session = gateway.get(intent(id).sessionId().orElseThrow());
        assertThat(session).isInstanceOfSatisfying(CallOutcome.Ok.class,
                ok -> assertThat(((CheckoutView) ok.value()).status()).isEqualTo(CheckoutStatus.CANCELED));
    }
}
