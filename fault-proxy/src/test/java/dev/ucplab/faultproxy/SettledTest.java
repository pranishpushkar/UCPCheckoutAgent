package dev.ucplab.faultproxy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.faultproxy.Action.CorruptResponse;
import dev.ucplab.faultproxy.Action.Delay;
import dev.ucplab.faultproxy.Action.DuplicateRequest;
import dev.ucplab.faultproxy.Action.FailBeforeForward;
import dev.ucplab.faultproxy.Action.ForwardThenDelayResponse;
import dev.ucplab.faultproxy.Action.ForwardThenDropResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The {@code settled} flag (§12): false while the proxy still has anything to do for a request, true exactly once
 * afterwards, whatever happened. The harness waits on it instead of sleeping for the longest hold.
 */
class SettledTest extends ProxyTestBase {

    private static final String COMPLETE = "/ucp/v1/checkout-sessions/cs_1/complete";

    static Stream<Arguments> everyAction() {
        return Stream.of(
                Arguments.of("forward", Optional.empty()),
                Arguments.of("delay", Optional.of(new Delay(0))),
                Arguments.of("fail_before_forward status", Optional.of(FailBeforeForward.status(503))),
                Arguments.of("fail_before_forward close", Optional.of(FailBeforeForward.closeConnection())),
                Arguments.of("forward_then_drop_response", Optional.of(new ForwardThenDropResponse())),
                Arguments.of("forward_then_delay_response", Optional.of(new ForwardThenDelayResponse(0))),
                Arguments.of("duplicate_request parallel",
                        Optional.of(new DuplicateRequest(DuplicateRequest.Mode.PARALLEL))),
                Arguments.of("duplicate_request sequential",
                        Optional.of(new DuplicateRequest(DuplicateRequest.Mode.SEQUENTIAL))),
                Arguments.of("corrupt_response", Optional.of(new CorruptResponse(CorruptResponse.Kind.TRUNCATE))));
    }

    // Covers: §12 settled (for plain forwarding and each of the six actions: false while the merchant holds the
    // request, true once the proxy has finished with it)
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyAction")
    void settled_falseWhileHeld_trueAfterwards(String name, Optional<Action> action) throws Exception {
        action.ifPresent(a -> proxy.install(new RuleSet(1, List.of(new Rule("r", Match.any(), a)))));
        CountDownLatch release = new CountDownLatch(1);
        upstream.respondWith((ex, a) -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            EchoUpstream.send(ex, 200, "{\"id\":\"cs_1\"}");
        });

        CompletableFuture<Void> reply = CompletableFuture.runAsync(() -> {
            try {
                post(COMPLETE, "{}");
            } catch (Exception e) {
                // A dropped or closed connection is one of the outcomes under test.
            }
        });
        boolean contactsMerchant = !(action.orElse(null) instanceof FailBeforeForward);
        if (contactsMerchant) {
            awaitTrue(() -> !upstream.arrivals().isEmpty());
            assertThat(proxy.log(0).getFirst().settled()).as("held at the merchant").isFalse();
        }
        release.countDown();
        reply.get(10, TimeUnit.SECONDS);

        LogEntry done = awaitEntry(LogEntry::settled);
        assertThat(done.settled()).isTrue();
        assertThat(Json.write(done.toJson())).contains("\"settled\":true");
    }

    // Covers: §12 settled (forward_then_delay_response: merchantStatus is already set during the hold, settled is not)
    @Test
    void settled_forwardThenDelayResponse_statusSetButNotSettledDuringTheHold() throws Exception {
        proxy.install(new RuleSet(1, List.of(new Rule("r", Match.any(), new ForwardThenDelayResponse(600)))));

        CompletableFuture<Object> reply = CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", COMPLETE, "{}", Map.of(), Duration.ofSeconds(10));
            } catch (Exception e) {
                return e;
            }
        });
        LogEntry held = awaitEntry(e -> e.merchantStatus().isPresent());

        assertThat(held.merchantStatus()).hasValue(200);
        assertThat(held.settled()).isFalse();
        reply.get(10, TimeUnit.SECONDS);
        assertThat(awaitEntry(LogEntry::settled).merchantStatus()).hasValue(200);
    }

    // Covers: §12 settled (delay: not settled while the request is held before forwarding)
    @Test
    void settled_delay_notSettledWhileHeldBeforeForwarding() throws Exception {
        proxy.install(new RuleSet(1, List.of(new Rule("r", Match.any(), new Delay(600)))));

        CompletableFuture<Void> reply = CompletableFuture.runAsync(() -> {
            try {
                post(COMPLETE, "{}");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        LogEntry held = awaitEntry(e -> true);

        assertThat(held.forwarded()).isFalse();
        assertThat(held.settled()).isFalse();
        reply.get(10, TimeUnit.SECONDS);
        assertThat(awaitEntry(LogEntry::settled).forwards()).isEqualTo(1);
    }

    // Covers: §12 settled (an upstream error settles the entry too)
    @Test
    void settled_afterAnUpstreamError() throws Exception {
        upstream.close();

        try {
            post(COMPLETE, "{}");
        } catch (Exception expected) {
            // The connection is closed with no reply.
        }

        LogEntry e = awaitEntry(LogEntry::settled);
        assertThat(e.upstreamError()).isTrue();
    }

    private LogEntry awaitEntry(Predicate<LogEntry> condition) throws InterruptedException {
        awaitTrue(() -> !proxy.log(0).isEmpty() && condition.test(proxy.log(0).getFirst()));
        return proxy.log(0).getFirst();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 5 s");
            }
            Thread.sleep(5);
        }
    }
}
