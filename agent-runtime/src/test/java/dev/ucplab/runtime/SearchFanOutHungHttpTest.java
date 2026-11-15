package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.InMemoryMerchantRegistry;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.faultproxy.FaultProxy;
import dev.ucplab.merchant.RuntimeMerchant;
import dev.ucplab.ucp.CatalogClient;
import dev.ucplab.ucp.CircuitBreaker;
import dev.ucplab.ucp.CircuitBreakers;
import dev.ucplab.ucp.JdkHttpTransport;
import dev.ucplab.ucp.Sleeper;
import dev.ucplab.ucp.UcpConfig;
import dev.ucplab.ucp.UcpHttpGateway;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The cancellation of a search that a merchant never answers (m6a risk 4; DESIGN 6.2, 8.2, 8.3, 19), through the real
 * {@code CatalogClient}, {@code JdkHttpTransport} and {@code RetryExecutor}: a merchant behind a fault proxy that holds
 * its catalog reply for a minute, and a limit of 50 ms. The interrupt must end the call once, count once against the
 * merchant's breaker, and leave no thread behind.
 */
class SearchFanOutHungHttpTest {

    private static final MerchantId HUNG = new MerchantId("shop-hung");
    private static final String HOLD_CATALOG = "{\"seed\":0,\"rules\":[{\"id\":\"hold\",\"match\":{\"method\":\"POST\","
            + "\"pathPattern\":\"/ucp/v1/catalog/search\"},\"action\":{\"type\":\"delay\",\"ms\":60000}}]}";

    /**
     * The live platform threads by name, without the JDK's own virtual-thread machinery (its carrier pool and its timed-park
     * "unparker", which the JDK starts on the first timed wait of any virtual thread and keeps) and the HTTP clients' own
     * pooled workers, which the JDK keeps for 60 s when idle. That a cancelled call really ended is shown by the breaker
     * having recorded it, not by these.
     */
    private static List<String> platformThreadNames() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> !t.isVirtual() && t.isAlive())
                .map(Thread::getName).filter(n -> !n.startsWith("VirtualThread-") && !n.startsWith("ForkJoinPool-")
                        && !n.startsWith("HttpClient-"))
                .sorted().toList();
    }

    /** The test's own admin client: made before the baseline, so its selector thread is not counted as the search's. */
    private static final HttpClient ADMIN = HttpClient.newHttpClient();

    private static void install(FaultProxy proxy, String rules) throws IOException, InterruptedException {
        HttpResponse<Void> r = ADMIN.send(HttpRequest.newBuilder(proxy.baseUrl().resolve("/_proxy/rules"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(rules)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(r.statusCode()).isBetween(200, 204);
    }

    private static void settle(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    // Covers: 6.2, 8.2, 8.3, 19, I6 (a merchant that never answers is left out after the limit: the call returns at about the
    // limit, the interrupted call ends once and counts once against the breaker, no thread is left behind, and the healthy
    // merchant's items come first in a fixed order)
    @Test
    void aMerchantThatNeverAnswers_isCancelledOnce_countedOnce_andLeavesNoThread() throws Exception {
        UcpConfig base = UcpConfig.defaults("profile=\"https://runtime.ucplab.dev/agent\"").withRate(1000, 1000);
        UcpConfig ucp = new UcpConfig(base.ucpAgent(), base.connectTimeout(), base.requestTimeout(), base.completeTimeout(),
                base.maxRetries(), base.backoffInitial(), base.backoffCap(), 2, base.breakerOpenFor(),
                base.maxResponseBytes(), base.rateBurst(), base.ratePerSecond());
        try (RuntimeLab healthy = new RuntimeLab(); RuntimeMerchant hungMerchant = RuntimeMerchant.start()) {
            byte[] hmac = new byte[32];
            new SecureRandom().nextBytes(hmac);
            try (FaultProxy hungProxy = FaultProxy.start(hungMerchant.baseUrl(), 0, hmac)) {
                Clock clock = Clock.systemUTC();
                MerchantEntry hungEntry = new MerchantEntry(HUNG, hungProxy.baseUrl(), "Hung", MerchantStatus.ACTIVE);
                CircuitBreakers breakers = new CircuitBreakers(ucp, clock);
                SequentialIdGenerator ids = new SequentialIdGenerator("r");
                UcpHttpGateway gateway = new UcpHttpGateway(hungEntry, ucp, new JdkHttpTransport(ucp), breakers, clock,
                        Sleeper.THREAD, new SplittableRandom(1), ids);
                CatalogClient hungCatalog = new CatalogClient(gateway, ucp, new JdkHttpTransport(ucp), breakers, clock,
                        Sleeper.THREAD, new SplittableRandom(2), ids);
                Map<MerchantId, CatalogGateway> catalogs = new LinkedHashMap<>();
                catalogs.put(RuntimeLab.SHOP, healthy.catalog);
                catalogs.put(HUNG, hungCatalog);
                ToolPorts ports = new ToolPorts(new InMemoryMerchantRegistry(List.of(healthy.shop, hungEntry)), catalogs,
                        healthy.ports().orchestrator());
                SearchCatalogTool tool = new SearchCatalogTool(ports);
                java.util.function.Supplier<ToolOutcome.Result> search = () -> (ToolOutcome.Result) tool.run(Stubs.CONTEXT,
                        Map.of("query", "mug", "max_results", 5L));

                // Warm up with nothing held: the HTTP client's own threads exist before the baseline is taken.
                ToolOutcome.Result warm = search.get();
                assertThat(warm.json()).doesNotContain("unavailable");
                install(hungProxy, "{\"seed\":0,\"rules\":[]}");
                settle(200);
                List<String> before = platformThreadNames();
                install(hungProxy, HOLD_CATALOG);
                ToolPorts quick = ports.withPerMerchant(Duration.ofMillis(50));
                SearchCatalogTool quickTool = new SearchCatalogTool(quick);

                long started = System.nanoTime();
                ToolOutcome.Result first = (ToolOutcome.Result) quickTool.run(Stubs.CONTEXT, Map.of("query", "mug", "max_results", 5L));
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

                assertThat(ms).as("returns at about the 50 ms limit").isBetween(45L, 1500L);
                assertThat(first.json()).contains("\"unavailable\":[\"shop-hung\"]").contains("\"merchant_id\":\"shop-1\"")
                        .doesNotContain("\"merchant_id\":\"shop-hung\"");
                assertThat(first.json().indexOf("shop-1")).isLessThan(first.json().indexOf("shop-hung"));
                settle(500);
                List<String> extra = new java.util.ArrayList<>(platformThreadNames());
                before.forEach(extra::remove);
                assertThat(extra).as("no thread left behind by the cancelled call").isEmpty();
                assertThat(breakers.forMerchant(HUNG).state()).as("one cancelled call is one failure, not two")
                        .isEqualTo(CircuitBreaker.State.CLOSED);

                quickTool.run(Stubs.CONTEXT, Map.of("query", "mug", "max_results", 5L));
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (breakers.forMerchant(HUNG).state() != CircuitBreaker.State.OPEN && System.nanoTime() < deadline) {
                    settle(20);
                }
                assertThat(breakers.forMerchant(HUNG).state()).as("the second cancelled call is the second failure")
                        .isEqualTo(CircuitBreaker.State.OPEN);
                settle(300);
                List<String> after = new java.util.ArrayList<>(platformThreadNames());
                before.forEach(after::remove);
                assertThat(after).as("no thread left behind after the second cancelled call").isEmpty();
            }
        }
    }
}
