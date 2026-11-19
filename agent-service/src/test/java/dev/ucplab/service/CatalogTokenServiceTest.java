package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.service.config.MerchantTransports;
import dev.ucplab.ucp.HttpCall;
import dev.ucplab.ucp.HttpReply;
import dev.ucplab.ucp.HttpTransport;
import dev.ucplab.ucp.mcp.McpStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The catalog source's token, fetched when the service starts (26.7, 20): the live check's first search timed out
 * because its 2 s included the token fetch. The catalog and the token endpoint are loopback stubs; nothing leaves the
 * JVM.
 */
class CatalogTokenServiceTest {

    private static final MerchantId CATALOG = new MerchantId("shopify-catalog");
    private static final String ITEM = "gid://shopify/ProductVariant/1000001";
    private static final String TOKEN_HOST = "api.shopify.com";

    @TempDir
    Path dir;

    private final McpStore store = new McpStore();
    private final List<HttpCall> tokenCalls = new CopyOnWriteArrayList<>();
    private volatile int tokenStatus = 200;
    private TestService service;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    /** The token endpoint's host gets a token (or {@link #tokenStatus}); every other call goes to the catalog stub. */
    private HttpTransport network() {
        return call -> {
            if (call.uri().getHost().equals(TOKEN_HOST)) {
                tokenCalls.add(call);
                return new HttpReply(tokenStatus, Map.of(), (tokenStatus == 200
                        ? "{\"access_token\":\"tok-1\",\"expires_in\":3600}" : "{}").getBytes(StandardCharsets.UTF_8));
            }
            return store.send(call);
        };
    }

    private void start() throws IOException {
        Path merchants = Files.writeString(dir.resolve("merchants.yaml"), """
                merchants:
                  - id: shopify-catalog
                    name: Shopify catalog
                    url: http://127.0.0.1:8191/api/ucp/mcp
                    transport: catalog
                    completion: link
                    credential_ref: env:SHOPIFY_CLIENT
                """);
        Path policy = Files.writeString(dir.resolve("policy.yaml"), """
                per_purchase_cap_minor: 100000
                daily_cap_minor: 200000
                """);
        Map<String, String> env = new HashMap<>();
        env.put("MERCHANTS_FILE", merchants.toString());
        env.put("POLICY_FILE", policy.toString());
        env.put("SHOPIFY_CLIENT", "client-1:secret-1");
        env.put("UCP_AGENT_PROFILE", "https://shopify.dev/ucp/agent-profiles/2026-04-08/valid-with-capabilities.json");
        env.put("UCP_AGENT_VERSION", "2026-04-08");
        service = TestService.start(dir, env, Optional.empty(), List.of(), Optional.empty(), Map.of(
                "testMerchantTransports", (MerchantTransports) this::network));
    }

    private static boolean within(Duration limit, BooleanSupplier condition) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    // Covers: 26.7, 20, 6.2 (the catalog's token is fetched when the service starts, before any call; the first catalog
    // read then fetches none, so its wait is the call's alone)
    @Test
    void theCatalogsToken_isFetchedAtStartup_andTheFirstReadFetchesNone() throws IOException {
        store.answerText("get_product", McpStore.catalogFixture("global_get_product_us.json"));
        start();

        assertThat(within(Duration.ofSeconds(5), () -> tokenCalls.size() == 1))
                .as("one token fetch at startup, before any catalog call").isTrue();
        assertThat(store.toolCalls()).isEmpty();

        Clock clock = service.bean(Clock.class);
        AuthContext alice = new AuthContext(new UserId("local-alice"), "alice@local.ucplab.dev", clock.instant(),
                "test-session");
        ProposalResult result = service.bean(DefaultOrchestrator.class).propose(alice, CATALOG,
                List.of(new ProposedLine(ITEM, 1)), "a board");

        assertThat(result.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(store.toolCalls()).extracting(McpStore.Sent::tool).containsExactly("get_product");
        assertThat(tokenCalls).as("the read used the token fetched at startup").hasSize(1);
    }

    // Covers: 26.7, 20 (a token endpoint that refuses does not stop the service from starting, and the fetch is not
    // repeated in a loop: it is tried again 30 s later)
    @Test
    void aRefusedTokenFetch_atStartup_neitherStopsTheService_norLoops() throws IOException, InterruptedException {
        tokenStatus = 503;
        start();

        assertThat(within(Duration.ofSeconds(5), () -> tokenCalls.size() == 1)).isTrue();
        Thread.sleep(1500);
        assertThat(tokenCalls).as("no second fetch within the 30 s retry").hasSize(1);
        assertThat(service.port()).isPositive();
    }
}
