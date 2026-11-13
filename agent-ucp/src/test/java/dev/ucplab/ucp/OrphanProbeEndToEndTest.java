package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.ApprovalSecret;
import dev.ucplab.core.approval.ApprovalTokens;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.approval.InMemoryApprovalRepository;
import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.core.orchestrator.OrchestratorConfig;
import dev.ucplab.core.policy.PolicyConfig;
import dev.ucplab.core.policy.PolicyUsage;
import dev.ucplab.core.policy.RequotePolicy;
import dev.ucplab.core.policy.RuleBasedPolicyEngine;
import dev.ucplab.faultproxy.Action;
import dev.ucplab.faultproxy.LogEntry;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The orphan-session probe (7.6 M3 note) through the real {@link UcpHttpGateway} and the real merchant: a create that
 * the merchant committed but whose answer was lost (dropped by the real fault proxy), then the probe under the same
 * key.
 */
class OrphanProbeEndToEndTest extends ContractTestBase {

    private static final UserId ALICE = new UserId("alice");
    private static final List<ProposedLine> TWO_SHIRTS = List.of(new ProposedLine("item_123", 2));

    // Covers: 7.6 (M3 note), 7.4, 8.2, A.1, I1 (the merchant commits the create and the answer is dropped: Uncertain,
    // FAILED_NOT_CHARGED with a cancel owed. The probe re-sends under the same key and the same body, the merchant
    // returns the stored session and not 409, the session is cancelled, and no order exists)
    @Test
    void probe_learnsStoredSessionAndCancelsIt_whenCreateWasCommittedButItsAnswerDropped() throws Exception {
        try (ProxyFixture proxy = new ProxyFixture(merchant.baseUrl())) {
            TestClock clock = new TestClock();
            InMemoryIntentRepository intents = new InMemoryIntentRepository(clock);
            InMemoryApprovalRepository approvals = new InMemoryApprovalRepository();
            UcpHttpGateway gateway = gateway(proxy.baseUrl(), CONFIG);
            PolicyConfig policyConfig = PolicyConfig.defaults();
            RuleBasedPolicyEngine policy = new RuleBasedPolicyEngine(policyConfig,
                    m -> m.equals(SHOP)
                            ? Optional.of(new MerchantEntry(SHOP, URI.create("http://127.0.0.1:8081"), "Shop",
                                    MerchantStatus.ACTIVE))
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
            SequentialIdGenerator ids = new SequentialIdGenerator("probe");
            OrchestratorConfig config = OrchestratorConfig.defaults();
            DefaultApprovalService approvalService = new DefaultApprovalService(intents, approvals,
                    new ApprovalTokens(ApprovalSecret.of("0123456789abcdef0123456789abcdef")), clock, ids, "worker-1",
                    config.lease(), DefaultApprovalService.DEFAULT_TTL, DefaultApprovalService.DEFAULT_MAX_AUTH_AGE);
            DefaultOrchestrator orchestrator = new DefaultOrchestrator(intents, approvals, approvalService, gateway,
                    policy, new RequotePolicy(policyConfig), PaymentInstrument.mockCard("pi_1", "tok_ok"), config, clock,
                    ids, "worker-1", new SplittableRandom(42));

            proxy.rules(ProxyFixture.rule("drop-create", "POST", ProxyFixture.CREATE,
                    new Action.ForwardThenDropResponse()));
            ProposalResult proposed = orchestrator.propose(
                    new AuthContext(ALICE, "alice@example.com", clock.instant(), "session-alice"), SHOP, TWO_SHIRTS,
                    "two red t-shirts");
            IntentId id = proposed.intentId();
            PurchaseIntent exhausted = intents.findForSystem(id).orElseThrow();
            assertThat(exhausted.state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
            assertThat(exhausted.sessionId()).isEmpty();
            assertThat(exhausted.cancelPending()).isTrue();

            proxy.clear();
            for (int i = 0; i < 20; i++) {
                StepResult r = orchestrator.advance(id);
                if (r.idle()) {
                    break;
                }
            }

            PurchaseIntent done = intents.findForSystem(id).orElseThrow();
            List<MerchantAttempt> creates = intents.attempts(id).stream().filter(a -> a.op() == AttemptOp.CREATE).toList();
            assertThat(creates).hasSize(2);
            assertThat(creates.get(0).status()).isEqualTo(AttemptStatus.UNCERTAIN);
            assertThat(creates.get(1).status()).isEqualTo(AttemptStatus.RESPONDED);
            assertThat(creates.get(1).httpStatus()).hasValueSatisfying(s -> assertThat(s).isBetween(200, 299));
            assertThat(creates).extracting(a -> a.idempotencyKey().orElseThrow())
                    .containsOnly(IdempotencyKey.derive(id, MutatingOp.CREATE, 0));
            assertThat(creates).extracting(a -> a.requestBody().orElseThrow())
                    .containsOnly(creates.get(0).requestBody().orElseThrow());
            assertThat(done.sessionId()).isPresent();
            assertThat(done.cancelPending()).isFalse();
            assertThat(done.state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
            CallOutcome<CheckoutView> session = gateway.get(done.sessionId().orElseThrow());
            assertThat(ok(session).status()).isEqualTo(CheckoutStatus.CANCELED);
            assertThat(intents.attempts(id)).noneMatch(a -> a.op() == AttemptOp.COMPLETE);
            assertThat(merchant.orders()).isEmpty();
            // On the wire: every create under the one key and the same bytes, and each dropped one was answered by
            // the merchant, so the session really existed before the probe learnt its id.
            List<LogEntry> wireCreates = proxy.to("POST", ProxyFixture.CREATE);
            assertThat(wireCreates).hasSize(5);
            assertThat(wireCreates).extracting(e -> e.idempotencyKey().orElseThrow())
                    .containsOnly(IdempotencyKey.derive(id, MutatingOp.CREATE, 0).value());
            assertThat(wireCreates).extracting(e -> e.bodyHmac().orElseThrow()).hasSize(5).containsOnly(
                    wireCreates.getFirst().bodyHmac().orElseThrow());
            assertThat(wireCreates.subList(0, 4)).allSatisfy(e -> {
                assertThat(e.ruleId()).contains("drop-create");
                assertThat(e.merchantStatus()).isPresent();
                assertThat(e.merchantStatus().getAsInt()).isBetween(200, 299);
            });
            assertThat(wireCreates.get(4).ruleId()).isEmpty();
        }
    }
}
