package dev.ucplab.eval;

import dev.ucplab.core.approval.ApprovalSecret;
import dev.ucplab.core.approval.ApprovalTokens;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.core.policy.PolicyConfig;
import dev.ucplab.core.policy.PolicyUsage;
import dev.ucplab.core.policy.RequotePolicy;
import dev.ucplab.core.policy.RuleBasedPolicyEngine;
import dev.ucplab.core.recovery.RecoveryLoop;
import dev.ucplab.core.recovery.RecoveryWorker;
import dev.ucplab.persistence.Database;
import dev.ucplab.persistence.SqlApprovalRepository;
import dev.ucplab.persistence.SqlIntentRepository;
import dev.ucplab.persistence.SqlRecoveryScan;
import dev.ucplab.ucp.CircuitBreakers;
import dev.ucplab.ucp.JdkHttpTransport;
import dev.ucplab.ucp.Sleeper;
import dev.ucplab.ucp.UcpConfig;
import dev.ucplab.ucp.UcpHttpGateway;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The whole agent for one run, built fresh (m4-plan section 2, step 2): a new database file, new repositories, a new
 * gateway with new circuit breakers pointed at the lane's proxy, the orchestrator, the approval service, and a
 * recovery worker and loop with a new worker id. Real time throughout: the system clock and {@code Thread.sleep}
 * (m4-plan section 3). Nothing carries over from one run to the next.
 */
final class AgentStack implements AutoCloseable {

    static final String UCP_AGENT = "profile=\"https://eval.ucplab.dev/agent\"";
    private static final int POOL_SIZE = 4;

    final Clock clock = Clock.systemUTC();
    final Database db;
    final SqlIntentRepository intents;
    final SqlApprovalRepository approvals;
    final DefaultApprovalService approvalService;
    final DefaultOrchestrator orchestrator;
    final RecoveryWorker worker;
    final RecoveryLoop loop;
    private final ScheduledExecutorService scheduler;

    /**
     * @param ids the run's id generator (a seeded sequence with a prefix unique to the lane and run, Decision 4)
     */
    AgentStack(Lane lane, Path dbFile, TimeScale scale, long seed, IdGenerator ids, String paymentToken) {
        db = Database.open(dbFile, POOL_SIZE);
        intents = new SqlIntentRepository(db, clock);
        approvals = new SqlApprovalRepository(db);
        SqlRecoveryScan scan = new SqlRecoveryScan(db);
        UcpConfig ucp = scale.ucp(UCP_AGENT);
        MerchantEntry shop = new MerchantEntry(Scenarios.SHOP, lane.agentFacingUrl(), "Lane shop", MerchantStatus.ACTIVE);
        UcpHttpGateway gateway = new UcpHttpGateway(shop, ucp, new JdkHttpTransport(ucp),
                new CircuitBreakers(ucp, clock), clock, Sleeper.THREAD, new SplittableRandom(seed), ids);
        PolicyConfig policyConfig = PolicyConfig.defaults();
        RuleBasedPolicyEngine policy = new RuleBasedPolicyEngine(policyConfig,
                m -> m.equals(Scenarios.SHOP) ? Optional.of(shop) : Optional.empty(), new FreshUserUsage(), clock);
        String workerId = RecoveryWorker.newWorkerId(ids);
        byte[] secret = new byte[ApprovalSecret.MIN_BYTES];
        new SecureRandom().nextBytes(secret);
        approvalService = new DefaultApprovalService(intents, approvals, new ApprovalTokens(ApprovalSecret.of(secret)),
                clock, ids, workerId, scale.orchestrator().lease(), DefaultApprovalService.DEFAULT_TTL,
                DefaultApprovalService.DEFAULT_MAX_AUTH_AGE);
        orchestrator = new DefaultOrchestrator(intents, approvals, approvalService, gateway, policy,
                new RequotePolicy(policyConfig), PaymentInstrument.mockCard("pi_eval", paymentToken),
                scale.orchestrator(), clock, ids, workerId, new SplittableRandom(~seed));
        worker = new RecoveryWorker(intents, scan, orchestrator, clock, Thread::sleep, Runnable::run, scale.recovery(),
                workerId);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "eval-recovery-" + workerId);
            t.setDaemon(true);
            return t;
        });
        loop = new RecoveryLoop(worker, scale.recovery(), scheduler);
    }

    /**
     * Policy usage for a run's user. Every run has a user of its own ({@code eval-<lane>-<scenario>-<run>}), so there
     * is no earlier spend or proposal to count; S4's second proposal is caught by dedupe before any limit matters.
     */
    private static final class FreshUserUsage implements PolicyUsage {
        @Override
        public long committedSpendMinor(UserId user, Instant from, Instant to) {
            return 0;
        }

        @Override
        public int proposalCount(UserId user, Instant from, Instant to) {
            return 0;
        }
    }

    @Override
    public void close() {
        loop.close();
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.close();
    }
}
