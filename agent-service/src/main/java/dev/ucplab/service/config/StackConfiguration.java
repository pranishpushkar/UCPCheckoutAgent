package dev.ucplab.service.config;

import dev.ucplab.core.approval.ApprovalTokens;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.crypto.HmacSha256;
import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.id.UuidV7IdGenerator;
import dev.ucplab.core.intent.IntentCursorCodec;
import dev.ucplab.core.merchant.MerchantInstruments;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.core.orchestrator.OrchestratorConfig;
import dev.ucplab.core.policy.RequotePolicy;
import dev.ucplab.core.policy.RuleBasedPolicyEngine;
import dev.ucplab.core.recovery.RecoveryConfig;
import dev.ucplab.core.recovery.RecoveryLoop;
import dev.ucplab.core.recovery.RecoveryWorker;
import dev.ucplab.persistence.Database;
import dev.ucplab.persistence.Migrations;
import dev.ucplab.persistence.SqlApprovalRepository;
import dev.ucplab.persistence.SqlIntentQueries;
import dev.ucplab.persistence.SqlIntentRepository;
import dev.ucplab.persistence.SqlPolicyUsage;
import dev.ucplab.persistence.SqlRecoveryScan;
import dev.ucplab.persistence.SqlRunRepository;
import dev.ucplab.persistence.SqlSessionRepository;
import dev.ucplab.persistence.SqlUserRepository;
import dev.ucplab.service.run.RunSupervisor;
import dev.ucplab.service.web.IntentDriver;
import dev.ucplab.ucp.CircuitBreakers;
import dev.ucplab.ucp.JdkHttpTransport;
import dev.ucplab.ucp.Sleeper;
import dev.ucplab.ucp.UcpConfig;
import dev.ucplab.ucp.UcpHttpGateway;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service's wiring (m6-plan section 2.4): explicit constructor calls, one bean each. The only class that takes the
 * system clock, a random source or a UUID directly (ArchitectureTest); everything else is given a {@link Clock} and an
 * {@link IdGenerator}. A test may register its own clock, ids, random source and recovery or orchestrator timings
 * before the context refreshes; each default here gives way to one.
 *
 * <p>No bean holds a lock (7.8). One worker id serves the approval service, the orchestrator and the recovery worker,
 * so the lease a decision takes is the one {@code driveNow} drives under. The database is migrated before it is opened,
 * and it is closed by {@link StackLifecycle}, after the drivers, never by Spring's own destroy step.
 */
@Configuration(proxyBeanMethods = false)
public class StackConfiguration {

    /** Sent as {@code UCP-Agent} on every merchant call (8.4). */
    static final String UCP_AGENT = "profile=\"https://ucplab.dev/agent\"";
    /** The label of the cursor key, derived from the approval secret (m6-plan Stage 2 review, point 1). */
    static final byte[] CURSOR_KEY_LABEL = "intent-cursor/v1".getBytes(StandardCharsets.US_ASCII);
    private static final int POOL_SIZE = 4;
    private static final int DRIVERS = 4;
    /** The lab merchant's mock card (Appendix A); a real handler arrives with M6a. */
    private static final String PAYMENT_TOKEN = "tok_ok";

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    SecureRandom random() {
        return new SecureRandom();
    }

    @Bean
    @ConditionalOnMissingBean
    IdGenerator ids(Clock clock, RandomGenerator random) {
        return new UuidV7IdGenerator(clock, random);
    }

    @Bean
    @ConditionalOnMissingBean
    RecoveryConfig recoveryConfig() {
        return RecoveryConfig.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    OrchestratorConfig orchestratorConfig() {
        return OrchestratorConfig.defaults();
    }

    /** One per process start (7.6), shared by the approval service, the orchestrator and the worker. */
    @Bean
    WorkerId workerId(IdGenerator ids) {
        return new WorkerId(RecoveryWorker.newWorkerId(ids));
    }

    @Bean(destroyMethod = "")
    Database database(ServiceProperties properties) {
        Path file = properties.databaseFile();
        Path parent = file.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("the database directory cannot be created", e);
        }
        Migrations.migrate(file);
        return Database.open(file, POOL_SIZE);
    }

    @Bean
    SqlIntentRepository intentRepository(Database db, Clock clock) {
        return new SqlIntentRepository(db, clock);
    }

    @Bean
    SqlApprovalRepository approvalRepository(Database db) {
        return new SqlApprovalRepository(db);
    }

    @Bean
    SqlRunRepository runRepository(Database db, Clock clock) {
        return new SqlRunRepository(db, clock);
    }

    @Bean
    SqlIntentQueries intentQueries(Database db) {
        return new SqlIntentQueries(db);
    }

    @Bean
    SqlSessionRepository sessionRepository(Database db, Clock clock) {
        return new SqlSessionRepository(db, clock);
    }

    @Bean
    SqlUserRepository userRepository(Database db) {
        return new SqlUserRepository(db);
    }

    @Bean
    IntentCursorCodec intentCursorCodec(ServiceProperties properties) {
        byte[] secret = properties.approvalSecretBytes();
        byte[] key = HmacSha256.compute(secret, CURSOR_KEY_LABEL);
        Arrays.fill(secret, (byte) 0);
        IntentCursorCodec codec = new IntentCursorCodec(key);
        Arrays.fill(key, (byte) 0);
        return codec;
    }

    @Bean
    UcpConfig ucpConfig() {
        return UcpConfig.defaults(UCP_AGENT);
    }

    /**
     * The one admission state of every merchant (8.3, 19): the checkout gateway and the catalog client of a merchant
     * share its breaker and rate limiter, because they share this instance.
     */
    @Bean
    CircuitBreakers circuitBreakers(UcpConfig ucp, Clock clock) {
        return new CircuitBreakers(ucp, clock);
    }

    @Bean
    UcpHttpGateway merchantGateway(ServiceProperties properties, UcpConfig ucp, CircuitBreakers breakers, Clock clock,
                                   RandomGenerator random, IdGenerator ids) {
        return new UcpHttpGateway(properties.merchant(), ucp, new JdkHttpTransport(ucp), breakers, clock,
                Sleeper.THREAD, random, ids);
    }

    @Bean
    StaticMerchantRegistry merchantRegistry(ServiceProperties properties, UcpHttpGateway merchantGateway) {
        return new StaticMerchantRegistry(properties.merchant(), merchantGateway);
    }

    @Bean
    DefaultApprovalService approvalService(SqlIntentRepository intents, SqlApprovalRepository approvals,
                                           ServiceProperties properties, Clock clock, IdGenerator ids,
                                           WorkerId workerId, OrchestratorConfig orchestratorConfig) {
        return new DefaultApprovalService(intents, approvals, new ApprovalTokens(properties.approvalKey()), clock, ids,
                workerId.value(), orchestratorConfig.lease(), DefaultApprovalService.DEFAULT_TTL,
                DefaultApprovalService.DEFAULT_MAX_AUTH_AGE);
    }

    /** The mock card of the lab (merchant {@code tok_ok}); a test registers its own to plant a canary token. */
    @Bean
    @ConditionalOnMissingBean
    PaymentInstrument paymentInstrument() {
        return PaymentInstrument.mockCard("pi_lab", PAYMENT_TOKEN);
    }

    @Bean
    DefaultOrchestrator orchestrator(SqlIntentRepository intents, SqlApprovalRepository approvals,
                                     DefaultApprovalService approvalService, UcpHttpGateway merchantGateway,
                                     StaticMerchantRegistry merchantRegistry, Database db,
                                     ServiceProperties properties, OrchestratorConfig orchestratorConfig, Clock clock,
                                     IdGenerator ids, WorkerId workerId, RandomGenerator random,
                                     PaymentInstrument paymentInstrument) {
        RuleBasedPolicyEngine policy = new RuleBasedPolicyEngine(properties.policy(), merchantRegistry::find,
                new SqlPolicyUsage(db), clock);
        // 8.5: the gateway above requires the lab handler, so the instrument must be for it, or the stack does not build.
        MerchantInstruments.requireMatchingHandler(properties.merchant().id(), UcpHttpGateway.PAYMENT_HANDLER,
                paymentInstrument);
        return new DefaultOrchestrator(intents, approvals, approvalService, merchantGateway, policy,
                new RequotePolicy(properties.policy()), paymentInstrument,
                orchestratorConfig, clock, ids, workerId.value(), random);
    }

    /** The drivers {@code driveNow} and the recovery passes hand intents to; drained on shutdown. */
    @Bean(destroyMethod = "")
    Drivers drivers() {
        return new Drivers(Executors.newFixedThreadPool(DRIVERS, named("intent-driver-", false)));
    }

    @Bean
    RecoveryWorker recoveryWorker(SqlIntentRepository intents, Database db, DefaultOrchestrator orchestrator,
                                  Clock clock, Drivers drivers, RecoveryConfig recoveryConfig,
                                  WorkerId workerId) {
        return new RecoveryWorker(intents, new SqlRecoveryScan(db), orchestrator, clock, Thread::sleep,
                drivers.executor(), recoveryConfig, workerId.value());
    }

    /** {@code driveNow} for the approval route; a test may register its own. */
    @Bean
    @ConditionalOnMissingBean
    IntentDriver intentDriver(RecoveryWorker recoveryWorker) {
        return recoveryWorker::driveNow;
    }

    @Bean(destroyMethod = "")
    ScheduledExecutorService recoveryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(named("recovery-loop-", true));
    }

    @Bean
    RecoveryLoop recoveryLoop(RecoveryWorker worker, RecoveryConfig recoveryConfig,
                              ScheduledExecutorService recoveryScheduler) {
        return new RecoveryLoop(worker, recoveryConfig, recoveryScheduler);
    }

    @Bean
    StackLifecycle stackLifecycle(RunSupervisor supervisor, RecoveryLoop loop, Drivers drivers,
                                  ScheduledExecutorService recoveryScheduler, Database db) {
        return new StackLifecycle(() -> supervisor.close(StackLifecycle.DEFAULT_GRACE), loop, drivers.executor(), recoveryScheduler, db, StackLifecycle.DEFAULT_GRACE);
    }

    private static ThreadFactory named(String prefix, boolean daemon) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + n.incrementAndGet());
            t.setDaemon(daemon);
            return t;
        };
    }

    /**
     * The pool intents are driven on. A type of its own, so it is never confused with another executor and Spring never
     * closes it on its own (an {@link ExecutorService} is {@code AutoCloseable}); {@link StackLifecycle} drains it.
     */
    public record Drivers(ExecutorService executor) {
        public Drivers {
            Objects.requireNonNull(executor, "executor");
        }
    }

    /** This process's lease owner id (7.6). */
    public record WorkerId(String value) {
        public WorkerId {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("worker id must not be blank");
            }
        }
    }
}
