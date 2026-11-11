package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.ALICE;
import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.T0;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.approval.ApprovalSecret;
import dev.ucplab.core.approval.ApprovalTokens;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.RecoveryScan;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import dev.ucplab.core.policy.PolicyConfig;
import dev.ucplab.core.recovery.RecoveryConfig;
import dev.ucplab.core.recovery.RecoveryWorker;
import dev.ucplab.core.policy.PolicyUsage;
import dev.ucplab.core.policy.RequotePolicy;
import dev.ucplab.core.policy.RuleBasedPolicyEngine;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * One crash matrix run: a clock, a store (any adapter, as {@link Stores}), a FaultyMerchantModel and any number of
 * processes over them. A process is an orchestrator and approval service whose repositories and gateway are crash
 * decorators; a new process over the same store is how a restart is modelled (S9). Time only moves when the harness
 * moves {@link #clock}; nothing sleeps.
 */
final class CrashHarness {

    /** The persistent side: what survives a crash. The scan is the adapter's {@link RecoveryScan}. */
    record Stores(IntentRepository intents, ApprovalRepository approvals, RecoveryScan scan) {}

    /** One process: its worker id, its services, and the crash plans of its three ports. */
    record Process(
            String id, DefaultOrchestrator orchestrator, DefaultApprovalService approvalService,
            CrashingIntentRepository intents, CrashingApprovalRepository approvals, CrashingGateway gateway,
            CrashPlan<CrashingIntentRepository.Op> intentPlan, CrashPlan<CrashingApprovalRepository.Op> approvalPlan,
            CrashPlan<CrashingGateway.Op> gatewayPlan) {

        boolean fired() {
            return intentPlan.fired() || approvalPlan.fired() || gatewayPlan.fired();
        }
    }

    /** The only intent a run creates: the first id the generator hands out. */
    static final IntentId INTENT = new IntentId("id-1");
    private static final ApprovalSecret SECRET = ApprovalSecret.of("0123456789abcdef0123456789abcdef");
    private static final int MAX_STEPS = 400;

    final MutableClock clock = new MutableClock(T0);
    final OrchestratorConfig config = OrchestratorConfig.defaults();
    final FaultyMerchantModel merchant;
    final Stores stores;
    private final SequentialIdGenerator ids = new SequentialIdGenerator("id");
    private final PaymentInstrument payment = PaymentInstrument.mockCard("pi_1", FaultyMerchantModel.TOKEN_OK);

    CrashHarness(java.util.function.Function<MutableClock, Stores> storeFactory) {
        this.stores = storeFactory.apply(clock);
        this.merchant = new FaultyMerchantModel(SHOP, FaultyMerchantModel.Mode.LAB, clock, new SequentialIdGenerator("m"));
    }

    Process process(String workerId) {
        CrashPlan<CrashingIntentRepository.Op> intentPlan = new CrashPlan<>(CrashingIntentRepository.Op.class);
        CrashPlan<CrashingApprovalRepository.Op> approvalPlan = new CrashPlan<>(CrashingApprovalRepository.Op.class);
        CrashPlan<CrashingGateway.Op> gatewayPlan = new CrashPlan<>(CrashingGateway.Op.class);
        CrashingIntentRepository intents = new CrashingIntentRepository(stores.intents(), intentPlan);
        CrashingApprovalRepository approvals = new CrashingApprovalRepository(stores.approvals(), approvalPlan);
        CrashingGateway gateway = new CrashingGateway(merchant, gatewayPlan);
        PolicyConfig policyConfig = PolicyConfig.defaults();
        RuleBasedPolicyEngine policy = new RuleBasedPolicyEngine(policyConfig,
                m -> m.equals(SHOP)
                        ? Optional.of(new MerchantEntry(SHOP, URI.create("http://127.0.0.1:8081"), "Shop", MerchantStatus.ACTIVE))
                        : Optional.empty(),
                new PolicyUsage() {
                    @Override
                    public long committedSpendMinor(dev.ucplab.core.identity.UserId user, Instant from, Instant to) {
                        return 0;
                    }

                    @Override
                    public int proposalCount(dev.ucplab.core.identity.UserId user, Instant from, Instant to) {
                        return 0;
                    }
                }, clock);
        DefaultApprovalService approvalService = new DefaultApprovalService(intents, approvals, new ApprovalTokens(SECRET),
                clock, ids, workerId, config.lease(), DefaultApprovalService.DEFAULT_TTL,
                DefaultApprovalService.DEFAULT_MAX_AUTH_AGE);
        DefaultOrchestrator orchestrator = new DefaultOrchestrator(intents, approvals, approvalService, gateway, policy,
                new RequotePolicy(policyConfig), payment, config, clock, ids, workerId, new SplittableRandom(42));
        return new Process(workerId, orchestrator, approvalService, intents, approvals, gateway, intentPlan,
                approvalPlan, gatewayPlan);
    }

    /** Arms {@code point} on the process. */
    void arm(Process p, Boundary.Point point) {
        switch (point.kind()) {
            case INTENT -> p.intentPlan().arm(CrashingIntentRepository.Op.valueOf(point.op()), point.phase(), point.nth());
            case APPROVAL -> p.approvalPlan().arm(CrashingApprovalRepository.Op.valueOf(point.op()), point.phase(), point.nth());
            case GATEWAY -> p.gatewayPlan().arm(CrashingGateway.Op.valueOf(point.op()), point.phase(), point.nth());
        }
    }

    AuthContext alice() {
        return new AuthContext(ALICE, "alice@example.com", clock.instant(), "session-alice");
    }

    void propose(Process p) {
        p.orchestrator().propose(alice(), SHOP, TWO_SHIRTS, "two red t-shirts");
    }

    /** The owner reads the pending approval and decides (23.2); nothing happens when there is none. */
    void approve(Process p, boolean approve) {
        Optional<PendingApproval> pending = p.approvalService().pending(alice(), INTENT);
        if (pending.isPresent()) {
            ApprovalDecision d = p.approvalService().decide(alice(), INTENT, pending.get().token(), approve);
            if (d instanceof ApprovalDecision.Busy) {
                throw new AssertionError("intent busy for the owner's own decision: " + d);
            }
        }
    }

    /** Calls advance until idle, moving the clock to each {@code nextAttemptAt} (7.5 "Who waits"). */
    void drive(Process p) {
        for (int i = 0; i < MAX_STEPS; i++) {
            StepResult r = p.orchestrator().advance(INTENT);
            if (r.idle()) {
                return;
            }
            r.nextAttemptAt().filter(t -> t.isAfter(clock.instant()))
                    .ifPresent(t -> clock.advance(Duration.between(clock.instant(), t)));
        }
        throw new AssertionError("intent did not settle in " + MAX_STEPS + " steps: " + intent());
    }

    /** One recovery pass: what a worker does every 5 s (7.6), driven to idle; the worker sleeps by moving the clock. */
    void recoverOnce(Process p) {
        new RecoveryWorker(p.intents(), stores.scan(), p.orchestrator(), clock, clock::advance, Runnable::run,
                new RecoveryConfig(Duration.ofSeconds(5), config.lease(), 100, MAX_STEPS), p.id()).runOnce();
    }

    /** The restart: time passes until the dead worker's lease has lapsed and one worker period has gone by (7.6). */
    void waitOutLeaseAndPeriod() {
        clock.advance(config.lease().plus(Duration.ofSeconds(5)).plusSeconds(1));
    }

    /**
     * Recovery by {@code p}: passes until the intent is terminal (or the script's owner has nothing to do), with the
     * owner's decision whenever the intent is back in AWAITING_APPROVAL.
     */
    void recover(Process p, Script script) {
        for (int round = 0; round < 8; round++) {
            recoverOnce(p);
            Optional<PurchaseIntent> current = intent();
            if (current.isEmpty() || current.get().state().isTerminal()) {
                return;
            }
            if (current.get().state() == IntentState.AWAITING_APPROVAL) {
                switch (script.ownerAction) {
                    case APPROVE -> approve(p, true);
                    case REJECT -> approve(p, false);
                    case NONE -> {
                        return;
                    }
                }
                drive(p);
            } else {
                waitOutLeaseAndPeriod();
            }
        }
        throw new AssertionError("intent did not settle: " + intent());
    }

    Optional<PurchaseIntent> intent() {
        return stores.intents().findForSystem(INTENT);
    }
}
