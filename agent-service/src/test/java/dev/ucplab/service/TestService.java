package dev.ucplab.service;

import dev.ucplab.core.recovery.RecoveryConfig;
import dev.ucplab.faultproxy.FaultProxy;
import dev.ucplab.merchant.LabMerchant;
import dev.ucplab.service.config.Environment;
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

    private TestService(LabMerchant merchant, FaultProxy proxy, ConfigurableApplicationContext context,
                        Map<String, String> env) {
        this.merchant = merchant;
        this.proxy = proxy;
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
        LabMerchant merchant = LabMerchant.start();
        byte[] hmac = new byte[32];
        new SecureRandom().nextBytes(hmac);
        FaultProxy proxy = FaultProxy.start(merchant.baseUrl(), 0, hmac);
        try {
            Map<String, String> env = baseEnv(dir);
            env.put("MERCHANT_URL", proxy.baseUrl().toString());
            env.putAll(extra);
            ApplicationContextInitializer<ConfigurableApplicationContext> beans = context -> {
                context.getBeanFactory().registerSingleton("testRecoveryConfig", NO_PASSES);
                clock.ifPresent(c -> context.getBeanFactory().registerSingleton("testClock", c));
            };
            ConfigurableApplicationContext context = ServiceApplication.start(Environment.of(env), List.of(beans),
                    "--server.port=0", "--server.address=127.0.0.1");
            return new TestService(merchant, proxy, context, env);
        } catch (RuntimeException e) {
            proxy.close();
            merchant.close();
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
        }
    }
}
