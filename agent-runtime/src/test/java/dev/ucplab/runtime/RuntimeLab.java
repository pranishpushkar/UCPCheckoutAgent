package dev.ucplab.runtime;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalSecret;
import dev.ucplab.core.approval.ApprovalService;
import dev.ucplab.core.approval.ApprovalTokens;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.approval.InMemoryApprovalRepository;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.id.UuidV7IdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.MerchantRegistry;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.core.orchestrator.OrchestratorConfig;
import dev.ucplab.core.policy.PolicyConfig;
import dev.ucplab.core.policy.PolicyUsage;
import dev.ucplab.core.policy.RequotePolicy;
import dev.ucplab.core.policy.RuleBasedPolicyEngine;
import dev.ucplab.faultproxy.FaultProxy;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.merchant.RuntimeMerchant;
import dev.ucplab.ucp.CatalogClient;
import dev.ucplab.ucp.CircuitBreakers;
import dev.ucplab.ucp.JdkHttpTransport;
import dev.ucplab.ucp.UcpConfig;
import dev.ucplab.ucp.UcpHttpGateway;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The real stack the tools run against (section 14, level 4): the reference merchant behind the real fault proxy, the
 * real UCP gateway and catalog client pointed at the proxy, and a real orchestrator and approval service over the
 * in-memory repositories, with UUIDv7 ids and the system clock. The approval service records every pending approval
 * it opens, so a test can look for its token, id and hash in what the tools returned.
 */
final class RuntimeLab implements AutoCloseable {

    static final MerchantId SHOP = new MerchantId("shop-1");
    static final String PAYMENT_TOKEN = "tok_ok";
    private static final UcpConfig UCP = UcpConfig.defaults("profile=\"https://runtime.ucplab.dev/agent\"");
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

    final Clock clock = Clock.systemUTC();
    /** Every id the stack generated, so a test can find each intent it made without a per-user listing. */
    final List<String> generated = new CopyOnWriteArrayList<>();
    private final IdGenerator uuids = new UuidV7IdGenerator(clock, new SecureRandom());
    final IdGenerator ids = () -> {
        String id = uuids.next();
        generated.add(id);
        return id;
    };
    final RuntimeMerchant merchant;
    final FaultProxy proxy;
    final InMemoryIntentRepository intents = new InMemoryIntentRepository(clock);
    final InMemoryApprovalRepository approvals = new InMemoryApprovalRepository();
    final List<PendingApproval> opened = new CopyOnWriteArrayList<>();
    final DefaultOrchestrator orchestrator;
    final CatalogClient catalog;
    final MerchantEntry shop;

    RuntimeLab() throws IOException {
        merchant = RuntimeMerchant.start();
        byte[] hmac = new byte[32];
        new SecureRandom().nextBytes(hmac);
        proxy = FaultProxy.start(merchant.baseUrl(), 0, hmac);
        shop = new MerchantEntry(SHOP, proxy.baseUrl(), "Runtime shop", MerchantStatus.ACTIVE);
        UcpHttpGateway gateway = new UcpHttpGateway(shop, UCP, new JdkHttpTransport(UCP),
                new CircuitBreakers(UCP, clock), clock, d -> { }, new SplittableRandom(1), ids);
        catalog = new CatalogClient(gateway, UCP, new JdkHttpTransport(UCP), new CircuitBreakers(UCP, clock), clock,
                d -> { }, new SplittableRandom(2), ids);
        byte[] secret = new byte[ApprovalSecret.MIN_BYTES];
        new SecureRandom().nextBytes(secret);
        String workerId = "runtime-lab";
        DefaultApprovalService service = new DefaultApprovalService(intents, approvals,
                new ApprovalTokens(ApprovalSecret.of(secret)), clock, ids, workerId,
                OrchestratorConfig.defaults().lease(), DefaultApprovalService.DEFAULT_TTL,
                DefaultApprovalService.DEFAULT_MAX_AUTH_AGE);
        RuleBasedPolicyEngine policy = new RuleBasedPolicyEngine(PolicyConfig.defaults(),
                m -> m.equals(SHOP) ? Optional.of(shop) : Optional.empty(), NO_USAGE, clock);
        orchestrator = new DefaultOrchestrator(intents, approvals, recording(service), gateway, policy,
                new RequotePolicy(PolicyConfig.defaults()), PaymentInstrument.mockCard("pi_1", PAYMENT_TOKEN),
                OrchestratorConfig.defaults(), clock, ids, workerId, new SplittableRandom(3));
    }

    /** The approval service, recording each pending approval it opens; deciding is delegated unchanged. */
    private ApprovalService recording(ApprovalService delegate) {
        return new ApprovalService() {
            @Override
            public PendingApproval open(PurchaseIntent intent, CheckoutView quote) {
                PendingApproval pending = delegate.open(intent, quote);
                opened.add(pending);
                return pending;
            }

            @Override
            public ApprovalDecision decide(AuthContext auth, IntentId id, String token, boolean approve) {
                return delegate.decide(auth, id, token, approve);
            }
        };
    }

    MerchantRegistry registry() {
        return new MerchantRegistry() {
            @Override
            public List<MerchantEntry> active() {
                return List.of(shop);
            }

            @Override
            public Optional<MerchantEntry> find(MerchantId id) {
                return id.equals(SHOP) ? Optional.of(shop) : Optional.empty();
            }

            @Override
            public MerchantProfile profile(MerchantId id) {
                throw new UnsupportedOperationException("the tools do not read profiles");
            }
        };
    }

    ToolPorts ports() {
        return new ToolPorts(registry(), Map.of(SHOP, catalog), orchestrator);
    }

    /** A signed-in user, as agent-service would build it; the session id stands for a credential reference. */
    AuthContext auth(String user) {
        return new AuthContext(new UserId(user), user + "@example.com", clock.instant(), "sess-" + user + "-secret");
    }

    /** The intents {@code user} owns, in creation order. */
    List<PurchaseIntent> intentsOf(UserId user) {
        return generated.stream().map(IntentId::new).map(intents::findForSystem).flatMap(Optional::stream)
                .filter(i -> i.owner().equals(user)).toList();
    }

    /** The approvals of {@code user}'s intents. */
    List<ApprovalRecord> approvalsOf(UserId user) {
        return intentsOf(user).stream().flatMap(i -> approvals.listForSystem(i.id()).stream()).toList();
    }

    /** The proxy's log once every entry is settled. */
    List<LogEntry> settledLog() {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            List<LogEntry> log = proxy.log(0);
            if (log.stream().allMatch(LogEntry::settled)) {
                return log;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("proxy log not settled");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Override
    public void close() {
        proxy.close();
        merchant.close();
    }
}
