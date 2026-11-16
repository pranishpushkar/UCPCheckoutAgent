package dev.ucplab.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.ucp.CircuitBreakers;
import dev.ucplab.ucp.HttpReply;
import dev.ucplab.ucp.HttpTransport;
import dev.ucplab.ucp.UcpConfig;
import dev.ucplab.ucp.mcp.McpStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * H5 C10: a 2026-04-08 setup works through configuration alone (26.2, 8.4, 19). The settings, {@code ucpConfig} and
 * {@code gatewayParts} are the service's own; only the network is replaced, by the scripted store answering with the
 * recordings and a token endpoint. Nothing leaves the JVM.
 */
class McpConfigurationTest {

    /** A 2026-08-25 agent profile the operator hosts (26.2). */
    static final String OUR_PROFILE = "https://agent.example/.well-known/ucp/agent.json";
    static final String SAMPLE_PROFILE = "https://shopify.dev/ucp/agent-profiles/2026-04-08/valid-with-capabilities.json";
    static final SessionId RECORDED = new SessionId("gid://shopify/Checkout/fakeCheckoutToken0000001?key=<redacted>");
    static final CreateCheckout ONE_BOARD = new CreateCheckout(
            List.of(new ProposedLine("gid://shopify/ProductVariant/51192065753174", 1)), Optional.empty());

    @TempDir
    Path dir;

    private final McpStore store = new McpStore();

    private Map<String, String> env(String pin) throws IOException {
        Path file = Files.writeString(dir.resolve("merchants.yaml"), """
                merchants:
                  - id: dev-store
                    name: Dev store
                    url: https://example-dev.myshopify.com
                    transport: mcp
                    completion: handoff
                    owned: true
                    shop_domain: example-dev.myshopify.com
                    credential_ref: env:SHOPIFY_CLIENT
                    ucp_versions: ["%s"]
                """.formatted(pin));
        Map<String, String> env = new HashMap<>();
        env.put("AGENT_PROFILE", "local");
        env.put("APPROVAL_SECRET", "s3cr3t-value-0123456789abcdefghijklmnop");
        env.put("DATABASE_URL", "jdbc:sqlite:" + dir.resolve("x.db"));
        env.put("MERCHANTS_FILE", file.toString());
        env.put("SHOPIFY_CLIENT", "client-1:secret-1");
        env.put("UCP_AGENT_PROFILE", OUR_PROFILE);   // 19: the default never reaches a real store
        return env;
    }

    /** The token endpoint's host gets a token; every other call goes to the store. */
    private HttpTransport network() {
        return call -> {
            if (call.uri().getHost().equals(ServiceProperties.DEFAULT_TOKEN_ENDPOINT.getHost())) {
                return new HttpReply(200, Map.of(), "{\"access_token\":\"tok-1\",\"expires_in\":3600}"
                        .getBytes(StandardCharsets.UTF_8));
            }
            return store.send(call);
        };
    }

    /** The service's own wiring of the first merchant, with {@link #network()} in place of the JDK client. */
    private MerchantGateway gateway(Map<String, String> env) {
        ServiceProperties properties = ServiceProperties.from(Environment.of(env));
        StackConfiguration stack = new StackConfiguration();
        UcpConfig ucp = stack.ucpConfig(properties);
        Clock clock = Clock.systemUTC();
        MerchantWiring.GatewayParts p = stack.gatewayParts(properties, ucp, new CircuitBreakers(ucp, clock), clock,
                new SplittableRandom(1), new SequentialIdGenerator("c"), Environment.of(env));
        MerchantWiring.GatewayParts offline = new MerchantWiring.GatewayParts(p.ucp(), p.breakers(), p.clock(), d -> { },
                p.random(), p.ids(), this::network, p.tokenEndpoint(), p.agentVersion(), p.buyerIp(), p.environment());
        // MerchantWiring.build, not gateway: until C7 an MCP merchant refuses to build a stack (HANDOFF_NOT_WIRED).
        return MerchantWiring.build(properties.merchants().getFirst(), offline);
    }

    private static IdempotencyKey key(MutatingOp op) {
        return IdempotencyKey.derive(new IntentId("i-1"), op, 0);
    }

    // Covers: 26.2, 8.4, 19, H5 C10 (UCP_AGENT_PROFILE and UCP_AGENT_VERSION at Shopify's 2026-04-08 sample, and the
    // merchant pinned to 2026-04-08: discovery accepts the store through supported_versions, each call carries that
    // profile in meta, and the recorded answers are read)
    @Test
    void a20260408Setup_worksThroughConfigurationAlone() throws IOException {
        Map<String, String> env = env("2026-04-08");
        env.put("UCP_AGENT_PROFILE", SAMPLE_PROFILE);
        env.put("UCP_AGENT_VERSION", "2026-04-08");
        store.answer("create_checkout", "create_checkout.json").answer("cancel_checkout", "cancel_checkout.json");
        MerchantGateway g = gateway(env);

        CallOutcome<CheckoutView> created = g.create(ONE_BOARD, key(MutatingOp.CREATE));
        CallOutcome<CheckoutView> canceled = g.cancel(RECORDED, key(MutatingOp.CANCEL));

        assertThat(created).isInstanceOfSatisfying(CallOutcome.Ok.class,
                ok -> assertThat(((CheckoutView) ok.value()).status()).isEqualTo(CheckoutStatus.INCOMPLETE));
        assertThat(canceled).isInstanceOfSatisfying(CallOutcome.Ok.class,
                ok -> assertThat(((CheckoutView) ok.value()).status()).isEqualTo(CheckoutStatus.CANCELED));
        assertThat(store.toolCalls()).hasSize(2).allSatisfy(s -> assertThat(((Map<?, ?>) s.arguments().get("meta"))
                .get("ucp-agent")).isEqualTo(Map.of("profile", SAMPLE_PROFILE)));
    }

    // Covers: 26.2, 8.1, H5 C10 (with the default version, 2026-08-25, and a profile of ours at it, the store's
    // 2026-04-08 answer is not read as if it were at 2026-08-25: the create is Uncertain, its retries (8.2) the same)
    @Test
    void theDefaultVersion_doesNotReadA20260408Answer() throws IOException {
        store.answer("create_checkout", "create_checkout.json");

        assertThat(gateway(env("2026-08-25")).create(ONE_BOARD, key(MutatingOp.CREATE)))
                .isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(store.toolCalls()).isNotEmpty().allSatisfy(s -> assertThat(((Map<?, ?>) s.arguments().get("meta"))
                .get("ucp-agent")).isEqualTo(Map.of("profile", OUR_PROFILE)));
        assertThat(store.toolCalls()).extracting(McpStore.Sent::body).containsOnly(store.toolCalls().getFirst().body());
    }
}
