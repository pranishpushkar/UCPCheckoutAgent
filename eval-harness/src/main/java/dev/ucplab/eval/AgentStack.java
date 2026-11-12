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
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
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
 *
 * <p>The same wiring serves the agent child process of S9 and S28 ({@link AgentMain}) and the extra workers of S14
 * ({@link #racer}).
 */
final class AgentStack implements AutoCloseable {

    static final String UCP_AGENT = "profile=\"https://eval.ucplab.dev/agent\"";
    private static final int POOL_SIZE = 4;
    /** A decision that holds the intent only for its own write (see the constructor). */
    static final Duration DECISION_ONLY = Duration.ofMillis(1);

    final Clock clock = Clock.systemUTC();
    final Database db;
    final SqlIntentRepository intents;
    final SqlApprovalRepository approvals;
    final DefaultApprovalService approvalService;
    final DefaultOrchestrator orchestrator;
    final RecoveryWorker worker;
    final RecoveryLoop loop;
    private final ScheduledExecutorService scheduler;
    private final Path dbFile;
    private final TimeScale scale;
    private final long seed;
    private final MerchantEntry shop;
    private final UcpConfig ucp;
    private final PolicyConfig policyConfig = PolicyConfig.defaults();
    private final RuleBasedPolicyEngine policy;
    private final ApprovalTokens tokens;
    private final PaymentInstrument instrument;

    /**
     * @param merchantUrl where the agent is pointed: the lane's proxy
     * @param ids the run's id generator (a seeded sequence with a prefix unique to the lane and run, Decision 4; a
     *     UUIDv7 generator in the child process)
     * @param approvalLease how long a decision holds the intent for this stack's worker. The orchestrator's lease, so
     *     the first complete follows at once; S9, S14 and S28 decide as a separate process would (the REST API in M6)
     *     and pass a lease of {@link #DECISION_ONLY}, so other workers can take the APPROVED intent at once
     */
    AgentStack(URI merchantUrl, Path dbFile, TimeScale scale, long seed, IdGenerator ids, String paymentToken,
               Duration approvalLease) {
        this.dbFile = dbFile;
        this.scale = scale;
        this.seed = seed;
        db = Database.open(dbFile, POOL_SIZE);
        intents = new SqlIntentRepository(db, clock);
        approvals = new SqlApprovalRepository(db);
        SqlRecoveryScan scan = new SqlRecoveryScan(db);
        ucp = scale.ucp(UCP_AGENT);
        shop = new MerchantEntry(Scenarios.SHOP, merchantUrl, "Lane shop", MerchantStatus.ACTIVE);
        policy = new RuleBasedPolicyEngine(policyConfig,
                m -> m.equals(Scenarios.SHOP) ? Optional.of(shop) : Optional.empty(), new FreshUserUsage(), clock);
        String workerId = RecoveryWorker.newWorkerId(ids);
        byte[] secret = new byte[ApprovalSecret.MIN_BYTES];
        new SecureRandom().nextBytes(secret);
        tokens = new ApprovalTokens(ApprovalSecret.of(secret));
        instrument = PaymentInstrument.mockCard("pi_eval", paymentToken);
        approvalService = new DefaultApprovalService(intents, approvals, tokens, clock, ids, workerId, approvalLease,
                DefaultApprovalService.DEFAULT_TTL, DefaultApprovalService.DEFAULT_MAX_AUTH_AGE);
        orchestrator = orchestrator(intents, approvals, approvalService, ids, workerId, new SplittableRandom(~seed));
        worker = new RecoveryWorker(intents, scan, orchestrator, clock, Thread::sleep, Runnable::run, scale.recovery(),
                workerId);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "eval-recovery-" + workerId);
            t.setDaemon(true);
            return t;
        });
        loop = new RecoveryLoop(worker, scale.recovery(), scheduler);
    }

    AgentStack(URI merchantUrl, Path dbFile, TimeScale scale, long seed, IdGenerator ids, String paymentToken) {
        this(merchantUrl, dbFile, scale, seed, ids, paymentToken, scale.orchestrator().lease());
    }

    String workerId() {
        return worker.workerId();
    }

    Path dbFile() {
        return dbFile;
    }

    private DefaultOrchestrator orchestrator(SqlIntentRepository repo, SqlApprovalRepository approvalRepo,
                                             DefaultApprovalService service, IdGenerator ids, String workerId,
                                             SplittableRandom jitter) {
        UcpHttpGateway gateway = new UcpHttpGateway(shop, ucp, new JdkHttpTransport(ucp),
                new CircuitBreakers(ucp, clock), clock, Sleeper.THREAD, new SplittableRandom(seed), ids);
        return new DefaultOrchestrator(repo, approvalRepo, service, gateway, policy, new RequotePolicy(policyConfig),
                instrument, scale.orchestrator(), clock, ids, workerId, jitter);
    }

    /**
     * One more worker on this stack's database file, as another agent process would be (S14): its own connection, its
     * own repositories, gateway and circuit breakers, its own orchestrator and its own worker id. The approval secret
     * is shared, so a re-quote it opened would still verify.
     *
     * @param ids an id generator with a prefix of its own (Decision 4)
     */
    Racer racer(IdGenerator ids, int index) {
        Database racerDb = Database.open(dbFile, 1);
        try {
            SqlIntentRepository repo = new SqlIntentRepository(racerDb, clock);
            SqlApprovalRepository approvalRepo = new SqlApprovalRepository(racerDb);
            String workerId = RecoveryWorker.newWorkerId(ids);
            DefaultApprovalService service = new DefaultApprovalService(repo, approvalRepo, tokens, clock, ids,
                    workerId, scale.orchestrator().lease(), DefaultApprovalService.DEFAULT_TTL,
                    DefaultApprovalService.DEFAULT_MAX_AUTH_AGE);
            return new Racer(racerDb, workerId, orchestrator(repo, approvalRepo, service, ids, workerId,
                    new SplittableRandom(seed * 31 + index)));
        } catch (RuntimeException e) {
            racerDb.close();
            throw e;
        }
    }

    /** One extra worker of S14. */
    record Racer(Database db, String workerId, DefaultOrchestrator orchestrator) implements AutoCloseable {
        @Override
        public void close() {
            db.close();
        }
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
