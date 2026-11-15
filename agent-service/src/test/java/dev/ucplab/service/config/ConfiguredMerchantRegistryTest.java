package dev.ucplab.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.merchant.DiscoveryFailedException;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.service.FakeClock;
import dev.ucplab.ucp.UcpHttpGateway;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The configured registry (DESIGN 19, 26.2, P1): status from the discovery check, fixed reasons, a startup check that runs
 * in parallel, and the single-flight re-check of a SUSPENDED merchant (m6a decision 2). The gateways are stubs that only
 * answer {@code discover}, so what is asserted is the registry's own behaviour.
 */
class ConfiguredMerchantRegistryTest {

    private static final MerchantId A = new MerchantId("merchant-a");
    private static final MerchantId B = new MerchantId("merchant-b");
    private static final MerchantId C = new MerchantId("merchant-c");

    private final FakeClock clock = new FakeClock(Instant.parse("2026-11-15T10:00:00Z"));

    private static MerchantSettings settings(MerchantId id, MerchantStatus fileStatus) {
        return new MerchantSettings(id, "Shop " + id.value(), URI.create("http://127.0.0.1:9101"), fileStatus,
                Optional.empty(), Set.of(), MerchantSettings.DEFAULT_VERSIONS, Optional.empty());
    }

    private static MerchantProfile profile(MerchantId id) {
        return new MerchantProfile(id, "2026-08-25", URI.create("http://127.0.0.1:9101/ucp/v1"),
                Set.of(UcpHttpGateway.CHECKOUT_CAPABILITY), Set.of(UcpHttpGateway.PAYMENT_HANDLER));
    }

    /** A gateway that answers only {@code discover}, through a behaviour the test swaps, and counts the asks. */
    private static final class Stub {
        final MerchantId id;
        final AtomicInteger asks = new AtomicInteger();
        volatile Function<MerchantId, MerchantProfile> behaviour;

        Stub(MerchantId id) {
            this.id = id;
            this.behaviour = ConfiguredMerchantRegistryTest::profile;
        }

        Stub failing(String reason) {
            behaviour = m -> {
                throw new DiscoveryFailedException("discovery failed for " + m.value(), reason);
            };
            return this;
        }

        MerchantGateway gateway() {
            return (MerchantGateway) Proxy.newProxyInstance(MerchantGateway.class.getClassLoader(),
                    new Class<?>[] {MerchantGateway.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("discover")) {
                            throw new UnsupportedOperationException(method.getName());
                        }
                        asks.incrementAndGet();
                        return behaviour.apply((MerchantId) args[0]);
                    });
        }
    }

    /** Polls an assertion for up to five seconds: a re-check runs on its own thread, so its result is not instant. */
    private static void eventually(Runnable assertion) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.onSpinWait();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private Map<MerchantId, Stub> stubs(MerchantId... ids) {
        Map<MerchantId, Stub> m = new LinkedHashMap<>();
        for (MerchantId id : ids) {
            m.put(id, new Stub(id));
        }
        return m;
    }

    private ConfiguredMerchantRegistry start(List<MerchantSettings> settings, Map<MerchantId, Stub> stubs) {
        return ConfiguredMerchantRegistry.start(settings, id -> stubs.get(id).gateway(), clock);
    }

    // Covers: 19, P1 (a merchant whose file status is active and whose discovery passes is ACTIVE, and is listed in file order)
    @Test
    void start_passingMerchants_areActive_inFileOrder() {
        Map<MerchantId, Stub> stubs = stubs(A, B, C);
        ConfiguredMerchantRegistry r = start(List.of(settings(A, MerchantStatus.ACTIVE), settings(B, MerchantStatus.ACTIVE),
                settings(C, MerchantStatus.ACTIVE)), stubs);

        assertThat(r.active()).extracting(MerchantEntry::id).containsExactly(A, B, C);
        assertThat(r.find(B).map(MerchantEntry::status)).contains(MerchantStatus.ACTIVE);
        assertThat(r.reason(B)).isEmpty();
    }

    // Covers: 19, 26.2, 8.5, P1 (each failing discovery reason becomes SUSPENDED with that fixed reason, mapped by the
    // constant and never by the message: the message here says something else on purpose)
    @Test
    void start_eachFailureReason_suspendsWithItsConstant_andLeavesTheMerchantOutOfActive() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(UcpHttpGateway.INCOMPATIBLE_VERSION, ConfiguredMerchantRegistry.INCOMPATIBLE_VERSION);
        expected.put(UcpHttpGateway.CAPABILITY_MISSING, ConfiguredMerchantRegistry.CAPABILITY_MISSING);
        expected.put(UcpHttpGateway.ENDPOINT_NOT_ALLOWED, ConfiguredMerchantRegistry.ENDPOINT_NOT_ALLOWED);
        expected.put(UcpHttpGateway.HANDLER_MISSING, ConfiguredMerchantRegistry.HANDLER_MISSING);
        expected.put(UcpHttpGateway.INVALID_PROFILE, ConfiguredMerchantRegistry.INVALID_PROFILE);
        expected.put(UcpHttpGateway.UNREACHABLE, ConfiguredMerchantRegistry.UNREACHABLE);
        expected.put("a code nobody knows", ConfiguredMerchantRegistry.UNREACHABLE);

        expected.forEach((code, reason) -> {
            Map<MerchantId, Stub> stubs = stubs(A, B);
            stubs.get(B).failing(code);
            ConfiguredMerchantRegistry r = start(List.of(settings(A, MerchantStatus.ACTIVE), settings(B, MerchantStatus.ACTIVE)), stubs);

            assertThat(r.active()).as(code).extracting(MerchantEntry::id).containsExactly(A);
            assertThat(r.find(B).map(MerchantEntry::status)).as(code).contains(MerchantStatus.SUSPENDED);
            assertThat(r.reason(B)).as(code).contains(reason);
        });
        assertThat(ConfiguredMerchantRegistry.INCOMPATIBLE_VERSION).isEqualTo("incompatible_version");
        assertThat(ConfiguredMerchantRegistry.CAPABILITY_MISSING).isEqualTo("capability_missing");
        assertThat(ConfiguredMerchantRegistry.ENDPOINT_NOT_ALLOWED).isEqualTo("endpoint_not_allowed");
        assertThat(ConfiguredMerchantRegistry.HANDLER_MISSING).isEqualTo("handler_missing");
    }

    // Covers: 19 (a merchant the file marks suspended is SUSPENDED with its own reason, is never asked, and is not re-checked)
    @Test
    void start_aMerchantSuspendedInTheFile_isNeverAsked() {
        Map<MerchantId, Stub> stubs = stubs(A, B);
        ConfiguredMerchantRegistry r = start(List.of(settings(A, MerchantStatus.ACTIVE), settings(B, MerchantStatus.SUSPENDED)), stubs);
        clock.advance(Duration.ofMinutes(10));

        assertThat(r.active()).extracting(MerchantEntry::id).containsExactly(A);
        assertThat(r.reason(B)).contains(ConfiguredMerchantRegistry.SUSPENDED_IN_FILE);
        assertThat(stubs.get(B).asks).hasValue(0);
    }

    // Covers: 19 (the startup check runs in parallel: three stubs that each wait for the other two to arrive can only finish
    // if all three are asked at once)
    @Test
    void start_checksEveryMerchantInParallel() {
        CountDownLatch allArrived = new CountDownLatch(3);
        Map<MerchantId, Stub> stubs = stubs(A, B, C);
        stubs.values().forEach(s -> s.behaviour = id -> {
            allArrived.countDown();
            try {
                if (!allArrived.await(5, TimeUnit.SECONDS)) {
                    throw new DiscoveryFailedException("the others never arrived");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DiscoveryFailedException("interrupted");
            }
            return profile(id);
        });

        ConfiguredMerchantRegistry r = start(List.of(settings(A, MerchantStatus.ACTIVE), settings(B, MerchantStatus.ACTIVE),
                settings(C, MerchantStatus.ACTIVE)), stubs);

        assertThat(r.active()).hasSize(3);
    }

    // Covers: 19 (a check that throws something unexpected suspends the merchant as unreachable; startup is never stopped)
    @Test
    void start_anUnexpectedException_suspendsTheMerchant_andStartupContinues() {
        Map<MerchantId, Stub> stubs = stubs(A, B);
        stubs.get(A).behaviour = id -> {
            throw new IllegalStateException("boom");
        };

        ConfiguredMerchantRegistry r = start(List.of(settings(A, MerchantStatus.ACTIVE), settings(B, MerchantStatus.ACTIVE)), stubs);

        assertThat(r.reason(A)).contains(ConfiguredMerchantRegistry.UNREACHABLE);
        assertThat(r.active()).extracting(MerchantEntry::id).containsExactly(B);
    }

    // Covers: 19 (a SUSPENDED merchant is re-checked on demand at most every 30 s, never before, and comes back with no restart)
    @Test
    void recheck_waitsThirtySeconds_thenARecoveredMerchantIsActiveAgain() {
        Map<MerchantId, Stub> stubs = stubs(A);
        stubs.get(A).failing(UcpHttpGateway.UNREACHABLE);
        ConfiguredMerchantRegistry r = start(List.of(settings(A, MerchantStatus.ACTIVE)), stubs);
        stubs.get(A).behaviour = ConfiguredMerchantRegistryTest::profile;

        clock.advance(Duration.ofSeconds(29));
        assertThat(r.active()).isEmpty();
        assertThat(stubs.get(A).asks).hasValue(1);

        clock.advance(Duration.ofSeconds(1));
        r.active();
        eventually(() -> assertThat(r.active()).hasSize(1));
        assertThat(r.reason(A)).isEmpty();
        assertThat(stubs.get(A).asks).hasValue(2);
    }

    // Covers: 19 (a merchant that is still failing at its re-check stays SUSPENDED, with the reason it has now, and waits a
    // further 30 s)
    @Test
    void recheck_aStillFailingMerchant_staysSuspended_withTheNewReason() {
        Map<MerchantId, Stub> stubs = stubs(A);
        stubs.get(A).failing(UcpHttpGateway.UNREACHABLE);
        ConfiguredMerchantRegistry r = start(List.of(settings(A, MerchantStatus.ACTIVE)), stubs);
        stubs.get(A).failing(UcpHttpGateway.HANDLER_MISSING);

        clock.advance(Duration.ofSeconds(31));
        r.find(A);
        eventually(() -> assertThat(r.reason(A)).contains(ConfiguredMerchantRegistry.HANDLER_MISSING));
        clock.advance(Duration.ofSeconds(29));
        r.active();

        assertThat(r.active()).isEmpty();
        assertThat(stubs.get(A).asks).hasValue(2);
    }

    // Covers: 19, 7.8, decision 2 (the re-check is single-flight: while one discovery is stalled, ten concurrent active()
    // calls, the winner's own included, return at once with the last known status, and the stub saw one request. Then a
    // recovery after the stalled check passes)
    @Test
    void recheck_isSingleFlight_andNobodyWaitsForTheStalledCheck() throws Exception {
        Map<MerchantId, Stub> stubs = stubs(A, B);
        stubs.get(A).failing(UcpHttpGateway.UNREACHABLE);
        ConfiguredMerchantRegistry r = start(List.of(settings(A, MerchantStatus.ACTIVE), settings(B, MerchantStatus.ACTIVE)), stubs);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        stubs.get(A).behaviour = id -> {
            entered.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return profile(id);
        };
        clock.advance(Duration.ofSeconds(31));
        int before = stubs.get(A).asks.get();

        List<List<MerchantEntry>> seen = new ArrayList<>();
        long started = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<List<MerchantEntry>>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(pool.submit(r::active));
            }
            for (Future<List<MerchantEntry>> f : calls) {
                seen.add(f.get(2, TimeUnit.SECONDS));
            }
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(elapsedMs).as("ten calls while a check is stalled").isLessThan(2000);
        assertThat(seen).allSatisfy(list -> assertThat(list).extracting(MerchantEntry::id).containsExactly(B));
        assertThat(stubs.get(A).asks.get() - before).as("discovery requests during the stall").isEqualTo(1);

        release.countDown();
        eventually(() -> assertThat(r.active()).extracting(MerchantEntry::id).containsExactly(A, B));
        assertThat(stubs.get(A).asks.get() - before).isEqualTo(1);
    }

    // Covers: 19, 20 (in-flight intents: profile() of a SUSPENDED merchant still goes to its gateway, and the registry asks
    // the gateway function only for merchants the file lists)
    @Test
    void profile_ofASuspendedMerchant_stillGoesToItsGateway() {
        Map<MerchantId, Stub> stubs = stubs(A);
        stubs.get(A).failing(UcpHttpGateway.UNREACHABLE);
        Set<MerchantId> asked = ConcurrentHashMap.newKeySet();
        ConfiguredMerchantRegistry r = ConfiguredMerchantRegistry.start(List.of(settings(A, MerchantStatus.ACTIVE)),
                id -> {
                    asked.add(id);
                    return stubs.get(id).gateway();
                }, clock);
        stubs.get(A).behaviour = ConfiguredMerchantRegistryTest::profile;

        assertThat(r.find(A).map(MerchantEntry::status)).contains(MerchantStatus.SUSPENDED);
        assertThat(r.profile(A)).isEqualTo(profile(A));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> r.profile(B)).isInstanceOf(IllegalArgumentException.class);
        assertThat(asked).containsExactly(A);
    }

    // Covers: 19, 25.4 (the MERCHANT_URL fallback: one merchant built from MERCHANT_URL answers find, active and profile
    // exactly as M6's static registry does)
    @Test
    void theMerchantUrlFallback_answersLikeM6sSingleRegistry() {
        MerchantEntry entry = new MerchantEntry(A, URI.create("http://127.0.0.1:9101"), "Merchant A", MerchantStatus.ACTIVE);
        Stub stub = new Stub(A);
        MerchantGateway gateway = stub.gateway();
        ConfiguredMerchantRegistry configured = ConfiguredMerchantRegistry.start(
                List.of(MerchantSettings.single(entry, URI.create("http://127.0.0.1:9001"))), id -> gateway, clock);

        assertThat(configured.active()).containsExactly(entry);
        assertThat(configured.find(A)).contains(entry);
        assertThat(configured.find(B)).isEmpty();
        assertThat(configured.profile(A)).isEqualTo(profile(A));
    }
}
