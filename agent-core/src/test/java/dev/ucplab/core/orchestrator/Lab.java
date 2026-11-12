package dev.ucplab.core.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalRepository;
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
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.InMemoryRecoveryScan;
import dev.ucplab.core.intent.RecoveryScan;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import dev.ucplab.core.policy.PolicyConfig;
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
 * One in-memory lab: clock, repositories and FaultyMerchantModel shared by any number of orchestrator instances
 * ("workers"). A fresh worker over the same repositories is how a test restarts the process (S9).
 */
final class Lab {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final MerchantId SHOP = new MerchantId("shop-1");
    static final UserId ALICE = new UserId("alice");
    static final UserId BOB = new UserId("bob");
    static final List<ProposedLine> TWO_SHIRTS = List.of(new ProposedLine("item_123", 2));
    private static final ApprovalSecret SECRET = ApprovalSecret.of("0123456789abcdef0123456789abcdef");
    private static final PolicyUsage NO_USAGE = new PolicyUsage() {
        @Override
        public long committedSpendMinor(UserId user, Instant from, Instant to) {
            return 0;
        }

        @Override
        public int proposalCount(UserId user, Instant from, Instant to) {
            return 0;
        }
    };

    final MutableClock clock = new MutableClock(T0);
    final IntentRepository intents;
    final ApprovalRepository approvals;
    final RecoveryScan scan;
    final FaultyMerchantModel merchant;
    final SequentialIdGenerator ids = new SequentialIdGenerator("id");
    final OrchestratorConfig config;
    private PaymentInstrument payment = PaymentInstrument.mockCard("pi_1", FaultyMerchantModel.TOKEN_OK);

    Lab() {
        this(OrchestratorConfig.defaults());
    }

    Lab(OrchestratorConfig config) {
        this(config, FaultyMerchantModel.Mode.LAB);
    }

    /** A lab whose merchant runs in {@code mode} (14.1 "Merchant strictness"). */
    Lab(OrchestratorConfig config, FaultyMerchantModel.Mode mode) {
        this(config, mode, Lab::inMemoryStores);
    }

    /** The in-memory store: what every Lab used before an adapter could be plugged in. */
    static CrashHarness.Stores inMemoryStores(MutableClock clock) {
        InMemoryIntentRepository intents = new InMemoryIntentRepository(clock);
        InMemoryApprovalRepository approvals = new InMemoryApprovalRepository();
        return new CrashHarness.Stores(intents, approvals, new InMemoryRecoveryScan(intents, approvals));
    }

    /**
     * A lab over any adapter: {@code storeFactory} builds the repositories and the recovery scan over this lab's clock
     * (the SQL property tests pass a SQLite store).
     */
    Lab(OrchestratorConfig config, FaultyMerchantModel.Mode mode,
            java.util.function.Function<MutableClock, CrashHarness.Stores> storeFactory) {
        CrashHarness.Stores stores = storeFactory.apply(clock);
        this.intents = stores.intents();
        this.approvals = stores.approvals();
        this.scan = stores.scan();
        this.config = config;
        this.merchant = new FaultyMerchantModel(SHOP, mode, clock, new SequentialIdGenerator("m"));
    }

    /** One orchestrator instance with its approval service, both using {@code workerId} as lease owner. */
    record Worker(String id, DefaultOrchestrator orchestrator, DefaultApprovalService approvalService) {}

    void payWith(String token) {
        payment = PaymentInstrument.mockCard("pi_1", token);
    }

    Worker worker(String workerId) {
        return worker(workerId, merchant);
    }

    Worker worker(String workerId, MerchantGateway gateway) {
        return worker(workerId, gateway, approvals);
    }

    /** A worker whose approval repository is {@code approvalRepository}, for tests that crash its writes. */
    Worker worker(String workerId, MerchantGateway gateway, ApprovalRepository approvalRepository) {
        return worker(workerId, gateway, approvalRepository, intents);
    }

    /** A worker over {@code intentRepository}, for tests that decorate the intent store (it must wrap {@link #intents}). */
    Worker worker(
            String workerId, MerchantGateway gateway, ApprovalRepository approvalRepository,
            IntentRepository intentRepository) {
        PolicyConfig policyConfig = PolicyConfig.defaults();
        RuleBasedPolicyEngine policy = new RuleBasedPolicyEngine(policyConfig,
                m -> m.equals(SHOP)
                        ? Optional.of(new MerchantEntry(SHOP, URI.create("http://127.0.0.1:8081"), "Shop", MerchantStatus.ACTIVE))
                        : Optional.empty(),
                NO_USAGE, clock);
        DefaultApprovalService approvalService = new DefaultApprovalService(intentRepository, approvalRepository,
                new ApprovalTokens(SECRET), clock, ids, workerId, config.lease(), DefaultApprovalService.DEFAULT_TTL,
                DefaultApprovalService.DEFAULT_MAX_AUTH_AGE);
        DefaultOrchestrator orchestrator = new DefaultOrchestrator(intentRepository, approvalRepository, approvalService, gateway, policy,
                new RequotePolicy(policyConfig), payment, config, clock, ids, workerId, new SplittableRandom(42));
        return new Worker(workerId, orchestrator, approvalService);
    }

    /** Alice, freshly signed in (T4). */
    AuthContext alice() {
        return new AuthContext(ALICE, "alice@example.com", clock.instant(), "session-alice");
    }

    AuthContext bob() {
        return new AuthContext(BOB, "bob@example.com", clock.instant(), "session-bob");
    }

    ProposalResult propose(Worker w, List<ProposedLine> lines) {
        return w.orchestrator().propose(alice(), SHOP, lines, "two red t-shirts");
    }

    /** The approver channel: Alice reads the pending approval and decides (23.2). */
    ApprovalDecision decide(Worker w, IntentId id, boolean approve) {
        PendingApproval pending = w.approvalService().pending(alice(), id).orElseThrow();
        return w.approvalService().decide(alice(), id, pending.token(), approve);
    }

    PendingApproval pending(Worker w, IntentId id) {
        return w.approvalService().pending(alice(), id).orElseThrow();
    }

    /**
     * Calls advance until it reports idle, doing the waiting itself: after each step the clock moves to the returned
     * {@code nextAttemptAt}, as RecoveryWorker will in M3 (7.5 "Who waits"). Fails after {@code maxSteps}.
     */
    IntentState drive(Worker w, IntentId id, int maxSteps) {
        for (int i = 0; i < maxSteps; i++) {
            StepResult r = w.orchestrator().advance(id);
            if (r.idle()) {
                return r.after();
            }
            waitUntilNextAttempt(r);
        }
        throw new AssertionError("intent " + id + " did not settle in " + maxSteps + " steps; state "
                + intent(id).state());
    }

    IntentState drive(Worker w, IntentId id) {
        return drive(w, id, 200);
    }

    /** The caller's wait (7.5): moves the clock to {@code nextAttemptAt} when it is in the future. */
    void waitUntilNextAttempt(StepResult r) {
        r.nextAttemptAt().filter(t -> t.isAfter(clock.instant()))
                .ifPresent(t -> clock.advance(Duration.between(clock.instant(), t)));
    }

    /** The intent's event log: only the in-memory repository exposes it. */
    List<dev.ucplab.core.intent.IntentEvent> events(IntentId id) {
        if (intents instanceof InMemoryIntentRepository memory) {
            return memory.events(id);
        }
        throw new UnsupportedOperationException("events are read through the in-memory repository only");
    }

    PurchaseIntent intent(IntentId id) {
        return intents.findForSystem(id).orElseThrow();
    }

    /** I2: the single order matches the approved snapshot's lines and currency, and its total is within the maximum. */
    void assertOrderMatchesLatestApproval(IntentId id) {
        var approval = approvals.findLatestForSystem(id).orElseThrow();
        assertThat(merchant.orders()).singleElement().satisfies(order -> {
            assertThat(order.sessionId()).isEqualTo(approval.snapshot().sessionId());
            assertThat(order.currency()).isEqualTo(approval.snapshot().currency());
            assertThat(order.lines()).extracting(l -> l.itemId() + "x" + l.quantity() + "@" + l.unitPriceMinor())
                    .containsExactlyInAnyOrderElementsOf(approval.snapshot().lines().stream()
                            .map(l -> l.itemId() + "x" + l.quantity() + "@" + l.unitPriceMinor()).toList());
            assertThat(order.totalMinor()).isLessThanOrEqualTo(approval.snapshot().maxTotalMinor());
        });
    }
}
