package dev.ucplab.faultproxy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.faultproxy.Action.Delay;
import dev.ucplab.faultproxy.Action.ForwardThenDelayResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Holds sleep on the request's own thread, so they must never hold up other requests: the server runs each request on
 * its own virtual thread (§12 Safety, 7.8).
 */
class ConcurrentHoldsTest extends ProxyTestBase {

    // Covers: §12 Safety, 7.8 (two requests each held 1 s by delay, sent together, finish together, not one after the
    // other)
    @Test
    void delay_twoHoldsRunSideBySide() throws Exception {
        proxy.install(new RuleSet(1, List.of(new Rule("hold", Match.any(), new Delay(1000)))));

        assertThat(twoAtOnce()).isLessThan(Duration.ofMillis(1800));
        assertThat(upstream.arrivals()).hasSize(2);
    }

    // Covers: §12 Safety, 7.8 (the same for forward_then_delay_response, which sleeps after the merchant answered)
    @Test
    void forwardThenDelayResponse_twoHoldsRunSideBySide() throws Exception {
        proxy.install(new RuleSet(1, List.of(new Rule("hold", Match.any(), new ForwardThenDelayResponse(1000)))));

        assertThat(twoAtOnce()).isLessThan(Duration.ofMillis(1800));
        assertThat(upstream.arrivals()).hasSize(2);
    }

    private Duration twoAtOnce() throws Exception {
        long start = System.nanoTime();
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> status("/ucp/v1/a"));
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> status("/ucp/v1/b"));
        assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(1000));
        return elapsed;
    }

    private int status(String path) {
        try {
            return post(path, "{}").statusCode();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
