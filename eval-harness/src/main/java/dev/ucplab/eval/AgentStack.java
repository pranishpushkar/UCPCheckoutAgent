package dev.ucplab.eval;

import dev.ucplab.core.approval.ApprovalSecret;
import dev.ucplab.core.approval.ApprovalTokens;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.InMemoryMerchantRegistry;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantGateways;
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
import dev.ucplab.ucp.registry.ConfiguredMerchantRegistry;
import dev.ucplab.ucp.registry.MerchantSettings;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * registry, a catalog client on each merchant's proxy, and this stack's orchestrator.
 *
 * <p>A stack over several merchants (m6a-plan C11) builds one gateway and one catalog client per merchant over one
 * {@link CircuitBreakers}, so a merchant has one breaker and one limiter (8.3), the orchestrator picks the gateway and the
 * instrument of the intent's merchant, and the registry is the real {@link ConfiguredMerchantRegistry}, started once the
 * merchants are up: P1 and the tools see what the service sees. A stack over one merchant keeps the one-entry registry of
 * M4 and M5, so its wire is exactly what it was (the registry's discovery would add a request to the proxy log).
 */
public final class AgentStack implements AutoCloseable {

    static final String UCP_AGENT = "profile=\"https://eval.ucplab.dev/agent\"";
    private static final int POOL_SIZE = 4;
    /** A decision that holds the intent only for its own write (see the constructor). */
    public static final Duration DECISION_ONLY = Duration.ofMillis(1);

    public final Clock clock = Clock.systemUTC();
    final Database db;
    public final SqlIntentRepository intents;
    final SqlApprovalRepository approvals;
    public final DefaultApprovalService approvalService;
    public final DefaultOrchestrator orchestrator;
    final RecoveryWorker worker;
    final RecoveryLoop loop;
    private final ScheduledExecutorService scheduler;
    private final Path dbFile;
    private final TimeScale scale;
    private final long seed;
    private final List<Merchant> merchants;
    private final Map<MerchantId, MerchantEntry> entries = new LinkedHashMap<>();
    private final MerchantRegistry registry;
    private final UcpConfig ucp;
    private final PolicyConfig policyConfig = PolicyConfig.defaults();
    private final RuleBasedPolicyEngine policy;
    private final ApprovalTokens tokens;
    private final String canaryFingerprint;
    private final Map<MerchantId, CatalogGateway> catalogs = new LinkedHashMap<>();
    private final Map<MerchantId, PaymentInstrument> instruments = new LinkedHashMap<>();
    /** One per stack, shared by the gateway and the catalog client (8.3, 19). */
    private final CircuitBreakers breakers;

    /**
     * One merchant of the stack: the id it has in the registry, where the agent is pointed (its proxy), and what differs
     * between merchants (8.5, 19).
     */
    public record Merchant(MerchantId id, URI url, MerchantWiring wiring) {
        public Merchant {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(wiring, "wiring");
        }
    }

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
    public AgentStack(URI merchantUrl, Path dbFile, TimeScale scale, long seed, IdGenerator ids, String paymentToken,
               Duration approvalLease, String canary, MerchantWiring wiring) {
        this(List.of(new Merchant(Scenarios.SHOP, merchantUrl, wiring)), dbFile, scale, seed, ids, paymentToken,
                approvalLease, canary);
    }

    /**
     * A stack over every merchant of {@code lane}, each pointed at its own proxy and wired as the lab merchant, with the
     * lease of an ordinary run.
     */
    static AgentStack forLane(Lane lane, Path dbFile, TimeScale scale, long seed, IdGenerator ids,
                              String paymentToken) {
        List<Merchant> merchants = new java.util.ArrayList<>();
        for (int i = 0; i < lane.merchantCount(); i++) {
            merchants.add(new Merchant(lane.merchantIds().get(i), lane.agentFacingUrl(i), MerchantWiring.LAB));
        }
        return new AgentStack(merchants, dbFile, scale, seed, ids, paymentToken, scale.orchestrator().lease(), "");
    }

    /**
     * @param merchants one or more; the first is the stack's primary one: its UCP-Agent string and shipping policy are
     *     the stack's, and every merchant's handler check of 8.5 runs here
     */
    public AgentStack(List<Merchant> merchants, Path dbFile, TimeScale scale, long seed, IdGenerator ids,
                      String paymentToken, Duration approvalLease, String canary) {
        this.merchants = List.copyOf(merchants);
        if (this.merchants.isEmpty()) {
            throw new IllegalArgumentException("a stack has at least one merchant");
        }
        MerchantWiring wiring = this.merchants.getFirst().wiring();
        this.dbFile = dbFile;
        this.scale = scale;
        this.seed = seed;
        db = Database.open(dbFile, POOL_SIZE);
        intents = new SqlIntentRepository(db, clock);
        approvals = new SqlApprovalRepository(db);
        SqlRecoveryScan scan = new SqlRecoveryScan(db);
        ucp = scale.ucp(wiring.ucpAgent());
        breakers = new CircuitBreakers(ucp, clock);
        for (Merchant m : this.merchants) {
            if (entries.put(m.id(), new MerchantEntry(m.id(), m.url(), "Lane shop", MerchantStatus.ACTIVE)) != null) {
                throw new IllegalArgumentException("a merchant is listed twice");
            }
            instruments.put(m.id(), m.wiring().instrument().apply(paymentToken));
            MerchantInstruments.requireMatchingHandler(m.id(), m.wiring().handlerId(), instruments.get(m.id()));
        }
        Map<MerchantId, MerchantGateway> discovery = new LinkedHashMap<>();
        int at = 0;
        for (Merchant m : this.merchants) {
            UcpHttpGateway gateway = gateway(m, random(seed ^ 0x5eed, at), ids);
            discovery.put(m.id(), gateway);
            catalogs.put(m.id(), new CatalogClient(gateway, ucp, new JdkHttpTransport(ucp), breakers,
                    clock, Sleeper.THREAD, random(seed * 7 + 1, at), ids));
            at++;
        }
        registry = this.merchants.size() == 1
                ? new InMemoryMerchantRegistry(List.of(entries.get(this.merchants.getFirst().id())))
                : ConfiguredMerchantRegistry.start(this.merchants.stream().map(m -> new MerchantSettings(m.id(),
                        "Lane shop", m.url(), MerchantStatus.ACTIVE, Optional.empty(), java.util.Set.of(),
                        m.wiring().ucpVersions(), Optional.empty())).toList(), discovery::get, clock);
        policy = new RuleBasedPolicyEngine(policyConfig, registry::find, new FreshUserUsage(), clock);
        String workerId = RecoveryWorker.newWorkerId(ids);
        byte[] random = new byte[ApprovalSecret.MIN_BYTES];
        new SecureRandom().nextBytes(random);
        byte[] planted = canary.getBytes(StandardCharsets.US_ASCII);
        byte[] secret = Arrays.copyOf(random, random.length + planted.length);
        System.arraycopy(planted, 0, secret, random.length, planted.length);
        tokens = new ApprovalTokens(ApprovalSecret.of(secret));
        canaryFingerprint = fingerprint(Arrays.copyOfRange(secret, random.length, secret.length));
        Arrays.fill(secret, (byte) 0);
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
        Map<MerchantId, UcpHttpGateway> gateways = new LinkedHashMap<>();
        int at = 0;
        for (Merchant m : merchants) {
            gateways.put(m.id(), gateway(m, random(seed, at++), ids));
        }
        return new DefaultOrchestrator(repo, approvalRepo, service, MerchantGateways.of(gateways), policy,
                new RequotePolicy(policyConfig), MerchantInstruments.of(instruments),
                merchants.getFirst().wiring().fulfillmentPolicy(), scale.orchestrator(), clock, ids, workerId, jitter);
    }

    /** Merchant 0 gets {@code base} as it always did; the others get seeds of their own derived from it. */
    private static SplittableRandom random(long base, int merchant) {
        return new SplittableRandom(merchant == 0 ? base : base ^ (merchant * 0x9E3779B97F4A7C15L));
    }

    private UcpHttpGateway gateway(Merchant m, SplittableRandom random, IdGenerator ids) {
        MerchantWiring wiring = m.wiring();
        return new UcpHttpGateway(entries.get(m.id()), ucp, wiring.transport().apply(new JdkHttpTransport(ucp)),
                breakers, clock, Sleeper.THREAD, random, ids, java.util.Set.of(), wiring.handlerId(),
                wiring.ucpVersions());
    }

    /**
     * One more worker on this stack's database file, as another agent process would be (S14): its own connection, its
     * own repositories, gateway and circuit breakers, its own orchestrator and its own worker id. The approval secret
     * is shared, so a re-quote it opened would still verify.
     *
     * @param ids an id generator with a prefix of its own (Decision 4)
     */
    public Racer racer(IdGenerator ids, int index) {
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
    public record Racer(Database db, String workerId, DefaultOrchestrator orchestrator) implements AutoCloseable {
        @Override
        public void close() {
            db.close();
        }
    }

    /**
     * The ports of an agent row's tools (m5-plan section 9): the stack's registry, each merchant's catalog through its
     * proxy, and this stack's orchestrator. No approval service and no gateway, so no tool can approve or complete (I6).
     */
    ToolPorts toolPorts() {
        return new ToolPorts(registry, Map.copyOf(catalogs), orchestrator);
    }

    /** The registry P1 and the tools read: the real one over several merchants, a one-entry one over one. */
    MerchantRegistry registry() {
        return registry;
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
        public long committedSpendMinor(UserId user, String currency, Instant from, Instant to) {
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
