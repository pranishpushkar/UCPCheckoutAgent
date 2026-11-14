package dev.ucplab.eval;

import dev.ucplab.core.approval.ApprovalSecret;
import dev.ucplab.core.approval.ApprovalTokens;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.MerchantRegistry;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.MerchantInstruments;
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
import dev.ucplab.runtime.ToolPorts;
import dev.ucplab.ucp.CatalogClient;
import dev.ucplab.ucp.CircuitBreakers;
import dev.ucplab.ucp.JdkHttpTransport;
import dev.ucplab.ucp.Sleeper;
import dev.ucplab.ucp.UcpConfig;
import dev.ucplab.ucp.UcpHttpGateway;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
 * ({@link #racer}). An agent row (M5) also gets the ports the agent runtime's tools call ({@link #toolPorts}): a
 * one-entry registry, a catalog client on the lane's proxy, and this stack's orchestrator.
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
    private final String canaryFingerprint;
    private final PaymentInstrument instrument;
    private final MerchantWiring wiring;
    private final CatalogClient catalog;
    /** One per stack, shared by the gateway and the catalog client (8.3, 19). */
    private final CircuitBreakers breakers;

    /**
     * @param merchantUrl where the agent is pointed: the lane's proxy
     * @param ids the run's id generator (a seeded sequence with a prefix unique to the lane and run, Decision 4; a
     *     UUIDv7 generator in the child process)
     * @param approvalLease how long a decision holds the intent for this stack's worker. The orchestrator's lease, so
     *     the first complete follows at once; S9, S14 and S28 decide as a separate process would (the REST API in M6)
     *     and pass a lease of {@link #DECISION_ONLY}, so other workers can take the APPROVED intent at once
     * @param canary the evaluation's I8 canary ({@link Canary}), appended to the approval secret's bytes: it changes
     *     only the HMAC, and must never appear in any output
     */
    AgentStack(URI merchantUrl, Path dbFile, TimeScale scale, long seed, IdGenerator ids, String paymentToken,
               Duration approvalLease, String canary) {
        this(merchantUrl, dbFile, scale, seed, ids, paymentToken, approvalLease, canary, MerchantWiring.LAB);
    }

    /** @param wiring the merchant's handler, versions, shipping policy and instrument; the handler check of 8.5 runs here */
    AgentStack(URI merchantUrl, Path dbFile, TimeScale scale, long seed, IdGenerator ids, String paymentToken,
               Duration approvalLease, String canary, MerchantWiring wiring) {
        this.wiring = wiring;
        this.dbFile = dbFile;
        this.scale = scale;
        this.seed = seed;
        db = Database.open(dbFile, POOL_SIZE);
        intents = new SqlIntentRepository(db, clock);
        approvals = new SqlApprovalRepository(db);
        SqlRecoveryScan scan = new SqlRecoveryScan(db);
        ucp = scale.ucp(UCP_AGENT);
        breakers = new CircuitBreakers(ucp, clock);
        shop = new MerchantEntry(Scenarios.SHOP, merchantUrl, "Lane shop", MerchantStatus.ACTIVE);
        policy = new RuleBasedPolicyEngine(policyConfig,
                m -> m.equals(Scenarios.SHOP) ? Optional.of(shop) : Optional.empty(), new FreshUserUsage(), clock);
        String workerId = RecoveryWorker.newWorkerId(ids);
        byte[] random = new byte[ApprovalSecret.MIN_BYTES];
        new SecureRandom().nextBytes(random);
        byte[] planted = canary.getBytes(StandardCharsets.US_ASCII);
        byte[] secret = Arrays.copyOf(random, random.length + planted.length);
        System.arraycopy(planted, 0, secret, random.length, planted.length);
        tokens = new ApprovalTokens(ApprovalSecret.of(secret));
        canaryFingerprint = fingerprint(Arrays.copyOfRange(secret, random.length, secret.length));
        Arrays.fill(secret, (byte) 0);
        instrument = wiring.instrument().apply(paymentToken);
        MerchantInstruments.requireMatchingHandler(Scenarios.SHOP, wiring.handlerId(), instrument);
        approvalService = new DefaultApprovalService(intents, approvals, tokens, clock, ids, workerId, approvalLease,
                DefaultApprovalService.DEFAULT_TTL, DefaultApprovalService.DEFAULT_MAX_AUTH_AGE);
        orchestrator = orchestrator(intents, approvals, approvalService, ids, workerId, new SplittableRandom(~seed));
        catalog = new CatalogClient(gateway(new SplittableRandom(seed ^ 0x5eed), ids), ucp,
                new JdkHttpTransport(ucp), breakers, clock, Sleeper.THREAD,
                new SplittableRandom(seed * 7 + 1), ids);
        worker = new RecoveryWorker(intents, scan, orchestrator, clock, Thread::sleep, Runnable::run, scale.recovery(),
                workerId);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "eval-recovery-" + workerId);
            t.setDaemon(true);
            return t;
        });
        loop = new RecoveryLoop(worker, scale.recovery(), scheduler);
    }

    AgentStack(URI merchantUrl, Path dbFile, TimeScale scale, long seed, IdGenerator ids, String paymentToken,
               Duration approvalLease) {
        this(merchantUrl, dbFile, scale, seed, ids, paymentToken, approvalLease, "");
    }

    AgentStack(URI merchantUrl, Path dbFile, TimeScale scale, long seed, IdGenerator ids, String paymentToken) {
        this(merchantUrl, dbFile, scale, seed, ids, paymentToken, scale.orchestrator().lease());
    }

    /** The stack's admission state of each merchant: a failing catalog opens the breaker checkout uses (8.3). */
    CircuitBreakers breakers() {
        return breakers;
    }

    String workerId() {
        return worker.workerId();
    }

    /**
     * SHA-256, in hex, of the part of this stack's approval secret that came from the canary. Not the canary and not
     * the secret: a child prints it on request ({@link AgentMain}) so a test can show the child's secret holds the
     * evaluation's canary without the canary reaching any output.
     */
    String canaryFingerprint() {
        return canaryFingerprint;
    }

    /** The fingerprint of {@code canary}, as {@link #canaryFingerprint()} would give for a stack planted with it. */
    static String fingerprintOf(String canary) {
        return fingerprint(canary.getBytes(StandardCharsets.US_ASCII));
    }

    private static String fingerprint(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Path dbFile() {
        return dbFile;
    }

    private DefaultOrchestrator orchestrator(SqlIntentRepository repo, SqlApprovalRepository approvalRepo,
                                             DefaultApprovalService service, IdGenerator ids, String workerId,
                                             SplittableRandom jitter) {
        UcpHttpGateway gateway = gateway(new SplittableRandom(seed), ids);
        return new DefaultOrchestrator(repo, approvalRepo, service, id -> gateway, policy, new RequotePolicy(policyConfig),
                id -> instrument, wiring.fulfillmentPolicy(), scale.orchestrator(), clock, ids, workerId, jitter);
    }

    private UcpHttpGateway gateway(SplittableRandom random, IdGenerator ids) {
        return new UcpHttpGateway(shop, ucp, new JdkHttpTransport(ucp), breakers, clock, Sleeper.THREAD, random, ids,
                java.util.Set.of(), wiring.handlerId(), wiring.ucpVersions());
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
     * The ports of an agent row's tools (m5-plan section 9): the lane's one merchant, its catalog through the proxy,
     * and this stack's orchestrator. No approval service and no gateway, so no tool can approve or complete (I6).
     */
    ToolPorts toolPorts() {
        MerchantRegistry registry = new MerchantRegistry() {
            @Override
            public List<MerchantEntry> active() {
                return List.of(shop);
            }

            @Override
            public Optional<MerchantEntry> find(MerchantId id) {
                return id.equals(Scenarios.SHOP) ? Optional.of(shop) : Optional.empty();
            }

            @Override
            public MerchantProfile profile(MerchantId id) {
                throw new UnsupportedOperationException("the tools do not read profiles");
            }
        };
        return new ToolPorts(registry, Map.of(Scenarios.SHOP, catalog), orchestrator);
    }

    /** How many intents {@code user} has in this run's database (S12's check, section 8's no-side-effect check). */
    int intentCount(UserId user) {
        return count("SELECT COUNT(*) FROM purchase_intent WHERE user_id = ?", user);
    }

    /** How many approval rows {@code user} has in this run's database (section 8). */
    int approvalCount(UserId user) {
        return count("SELECT COUNT(*) FROM approval WHERE user_id = ?", user);
    }

    private int count(String sql, UserId user) {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, user.value());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
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
