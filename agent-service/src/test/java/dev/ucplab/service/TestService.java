package dev.ucplab.service;

import dev.ucplab.core.recovery.RecoveryConfig;
import dev.ucplab.faultproxy.FaultProxy;
import dev.ucplab.merchant.LabMerchant;
import dev.ucplab.service.config.Environment;
import dev.ucplab.service.web.IntentDriver;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * The whole service on a free port, in this JVM, against the reference merchant behind the real fault proxy (m6-plan
 * section 2.4): the service's own beans, a fresh SQLite file, a random approval secret. The recovery loop's period is an
 * hour, so its one pass at startup runs on an empty database and no pass runs during a test unless the test asks for
 * one; that is how a test shows an intent completed through {@code driveNow} alone.
 */
final class TestService implements AutoCloseable {

    /** Long enough that no scheduled pass runs during a test (the lease must be at least twice the period, 7.6). */
    static final RecoveryConfig NO_PASSES = new RecoveryConfig(Duration.ofHours(1), Duration.ofHours(2), 50, 500);

    final LabMerchant merchant;
    final FaultProxy proxy;
    final ConfigurableApplicationContext context;
    final Map<String, String> env;
    /** Merchant B and its proxy, when started with {@link #startMerchants}; otherwise null. */
    final LabMerchant merchantB;
    final FaultProxy proxyB;

    private TestService(LabMerchant merchant, FaultProxy proxy, ConfigurableApplicationContext context,
                        Map<String, String> env) {
        this(merchant, proxy, null, null, context, env);
    }

    private TestService(LabMerchant merchant, FaultProxy proxy, LabMerchant merchantB, FaultProxy proxyB,
                        ConfigurableApplicationContext context, Map<String, String> env) {
        this.merchant = merchant;
        this.proxy = proxy;
        this.merchantB = merchantB;
        this.proxyB = proxyB;
        this.context = context;
        this.env = env;
    }

    static Map<String, String> baseEnv(Path dir) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        Map<String, String> env = new HashMap<>();
        env.put("AGENT_PROFILE", "local");
        env.put("DATABASE_URL", "jdbc:sqlite:" + dir.resolve("service.db"));
        env.put("APPROVAL_SECRET", HexFormat.of().formatHex(secret));
        return env;
    }

    static TestService start(Path dir) throws IOException {
        return start(dir, Map.of(), Optional.empty());
    }

    static TestService start(Path dir, Map<String, String> extra, Optional<Clock> clock) throws IOException {
        return start(dir, extra, clock, List.of());
    }

    static TestService start(Path dir, Map<String, String> extra, Optional<Clock> clock, List<Class<?>> beans)
            throws IOException {
        return start(dir, extra, clock, beans, Optional.empty());
    }

    /**
     * @param beans extra bean classes, such as a test-only controller
     * @param driver replaces {@code driveNow} on the approval route, to show what happens when it starts nothing
     */
    static TestService start(Path dir, Map<String, String> extra, Optional<Clock> clock, List<Class<?>> beans,
                             Optional<IntentDriver> driver) throws IOException {
        return start(dir, extra, clock, beans, driver, Map.of());
    }

    /** With extra singletons by bean name, such as a {@code ModelClient} or a {@code RunSupervisorConfig}. */
    static TestService start(Path dir, Map<String, String> extra, Optional<Clock> clock, List<Class<?>> beans,
                             Optional<IntentDriver> driver, Map<String, Object> singletons) throws IOException {
        return start(dir, extra, clock, beans, driver, singletons, 0);
    }

    /** On a given port (0 for any): a client whose {@code Origin} must be the base URL needs to know it first. */
    static TestService start(Path dir, Map<String, String> extra, Optional<Clock> clock, List<Class<?>> beans,
                             Optional<IntentDriver> driver, Map<String, Object> singletons, int port)
            throws IOException {
        LabMerchant merchant = LabMerchant.start();
        byte[] hmac = new byte[32];
        new SecureRandom().nextBytes(hmac);
        FaultProxy proxy = FaultProxy.start(merchant.baseUrl(), 0, hmac);
        try {
            Map<String, String> env = baseEnv(dir);
            env.put("MERCHANT_URL", proxy.baseUrl().toString());
            env.put("MERCHANT_LAB_URL", merchant.baseUrl().toString());
            env.putAll(extra);
            ApplicationContextInitializer<ConfigurableApplicationContext> overrides = context -> {
                context.getBeanFactory().registerSingleton("testRecoveryConfig", NO_PASSES);
                clock.ifPresent(c -> context.getBeanFactory().registerSingleton("testClock", c));
                driver.ifPresent(d -> context.getBeanFactory().registerSingleton("testIntentDriver", d));
                singletons.forEach((name, bean) -> context.getBeanFactory().registerSingleton(name, bean));
                beans.forEach(b -> ((GenericApplicationContext) context).registerBean(b));
            };
            ConfigurableApplicationContext context = ServiceApplication.start(Environment.of(env), List.of(overrides),
                    "--server.port=" + port, "--server.address=127.0.0.1");
            return new TestService(merchant, proxy, context, env);
        } catch (RuntimeException e) {
            proxy.close();
            merchant.close();
            throw e;
        }
    }

    /**
     * Two merchants (m6a C7): merchant-a on the built-in catalog and merchant-b on {@code dev/catalogs/b.json}, each in
     * this JVM behind its own fault proxy, listed in a generated {@code merchants.yaml} ({@code MERCHANTS_FILE}).
     *
     * @param bStatus {@code active} or {@code suspended}, as the file says it
     * @param withB false lists merchant-a only (a merchant dropped from the file)
     */
    static TestService startMerchants(Path dir, Map<String, String> extra, Optional<Clock> clock, String bStatus,
                                      boolean withB) throws IOException {
        LabMerchant a = LabMerchant.start();
        LabMerchant b = LabMerchant.start(Path.of("..", "dev", "catalogs", "b.json"));
        byte[] hmac = new byte[32];
        new SecureRandom().nextBytes(hmac);
        return startOn(dir, extra, clock, bStatus, withB, a, b, FaultProxy.start(a.baseUrl(), 0, hmac),
                FaultProxy.start(b.baseUrl(), 0, hmac));
    }

    /**
     * A restart of the service against the merchants and proxies of {@code previous}, which keep running: their sessions
     * and orders survive, as a real merchant's do when the agent restarts. Closes only the previous service's context.
     */
    static TestService restartMerchants(TestService previous, Path dir, Map<String, String> extra, Optional<Clock> clock,
                                        String bStatus, boolean withB) throws IOException {
        previous.context.close();
        return startOn(dir, extra, clock, bStatus, withB, previous.merchant, previous.merchantB, previous.proxy,
                previous.proxyB);
    }

    private static TestService startOn(Path dir, Map<String, String> extra, Optional<Clock> clock, String bStatus,
                                       boolean withB, LabMerchant a, LabMerchant b, FaultProxy proxyA, FaultProxy proxyB)
            throws IOException {
        try {
            StringBuilder yaml = new StringBuilder("merchants:\n")
                    .append("  - id: merchant-a\n    name: Merchant A\n    url: ").append(proxyA.baseUrl())
                    .append("\n    lab_url: ").append(a.baseUrl()).append("\n");
            if (withB) {
                yaml.append("  - id: merchant-b\n    name: Merchant B\n    status: ").append(bStatus)
                        .append("\n    url: ").append(proxyB.baseUrl()).append("\n    lab_url: ").append(b.baseUrl())
                        .append("\n");
            }
            Path file = java.nio.file.Files.writeString(dir.resolve("merchants.yaml"), yaml);
            Map<String, String> env = baseEnv(dir);
            env.put("MERCHANTS_FILE", file.toString());
            env.putAll(extra);
            ApplicationContextInitializer<ConfigurableApplicationContext> overrides = context -> {
                context.getBeanFactory().registerSingleton("testRecoveryConfig", NO_PASSES);
                clock.ifPresent(c -> context.getBeanFactory().registerSingleton("testClock", c));
            };
            ConfigurableApplicationContext context = ServiceApplication.start(Environment.of(env), List.of(overrides),
                    "--server.port=0", "--server.address=127.0.0.1");
            return new TestService(a, proxyA, b, proxyB, context, env);
        } catch (RuntimeException e) {
            proxyA.close();
            proxyB.close();
            a.close();
            b.close();
            throw e;
        }
    }

    <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    int port() {
        ConfigurableEnvironment e = context.getEnvironment();
        return Integer.parseInt(e.getProperty("local.server.port"));
    }

    @Override
    public void close() {
        try {
            context.close();
        } finally {
            proxy.close();
            merchant.close();
            if (proxyB != null) {
                proxyB.close();
                merchantB.close();
            }
        }
    }
}
