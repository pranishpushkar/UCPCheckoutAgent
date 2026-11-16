package dev.ucplab.ucp;

import static dev.ucplab.ucp.McpMerchantGatewayTest.CANCEL_KEY;
import static dev.ucplab.ucp.McpMerchantGatewayTest.CONFIG;
import static dev.ucplab.ucp.McpMerchantGatewayTest.CREATE_KEY;
import static dev.ucplab.ucp.McpMerchantGatewayTest.ONE_BOARD;
import static dev.ucplab.ucp.McpMerchantGatewayTest.RECORDED;
import static dev.ucplab.ucp.McpMerchantGatewayTest.SHOP;
import static dev.ucplab.ucp.McpMerchantGatewayTest.entry;
import static dev.ucplab.ucp.McpMerchantGatewayTest.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.DiscoveryFailedException;
import dev.ucplab.ucp.auth.ClientCredentials;
import dev.ucplab.ucp.auth.TokenProvider;
import dev.ucplab.ucp.mcp.McpStore;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * H5 C10: the version an MCP merchant is spoken in (26.2, 8.4). The recorded store declares 2026-08-25 and lists
 * 2026-04-08 under {@code supported_versions}; its recorded answers are at 2026-04-08, the version of the agent profile
 * the spike sent.
 */
class McpVersionsTest {

    private final McpMerchantGatewayTest setup = new McpMerchantGatewayTest();
    private final McpStore store = setup.store;

    private McpMerchantGateway gateway(Set<String> pin, String agentVersion) {
        ClientCredentials credentials = new ClientCredentials("client-1", "secret-1");
        TokenProvider tokens = new TokenProvider(URI.create("https://api.shopify.com/auth/access_token"), credentials,
                setup.tokenEndpoint, setup.clock, Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofSeconds(5));
        return new McpMerchantGateway(entry(Completion.HANDOFF), CONFIG, store, new CircuitBreakers(CONFIG, setup.clock),
                setup.clock, d -> { }, new SplittableRandom(3), new SequentialIdGenerator("m"), Set.of(), "mock_pay_1", pin,
                agentVersion, tokens, credentials, Optional.empty());
    }

    // Covers: 26.2 (a merchant pinned to 2026-04-08 is accepted when its profile lists that version under
    // supported_versions, and the recorded 2026-04-08 answers are read)
    @Test
    void aMerchantPinnedTo20260408_isAcceptedThroughSupportedVersions_andItsAnswersAreRead() {
        store.answer("create_checkout", "create_checkout.json").answer("cancel_checkout", "cancel_checkout.json");
        McpMerchantGateway g = gateway(Set.of("2026-04-08"), "2026-04-08");

        assertThat(ok(g.create(ONE_BOARD, CREATE_KEY)).status()).isEqualTo(CheckoutStatus.INCOMPLETE);
        assertThat(ok(g.cancel(RECORDED, CANCEL_KEY)).status()).isEqualTo(CheckoutStatus.CANCELED);
        assertThat(g.discover(SHOP).ucpVersion()).isEqualTo("2026-08-25");
        assertThat(g.discover(SHOP).supportedVersions()).containsExactlyInAnyOrder("2026-01-23", "2026-04-08");
    }

    // Covers: 26.2 (the agent's version must be pinned for the merchant: a merchant pinned to 2026-08-25 only is not
    // spoken to in 2026-04-08, though its profile declares both; nothing is sent)
    @Test
    void theAgentsVersion_notPinnedForTheMerchant_isIncompatible() {
        assertThatThrownBy(() -> gateway(Set.of("2026-08-25"), "2026-04-08").discover(SHOP))
                .isInstanceOfSatisfying(DiscoveryFailedException.class,
                        e -> assertThat(e.reason()).isEqualTo(UcpHttpGateway.INCOMPATIBLE_VERSION));
        assertThat(store.toolCalls()).isEmpty();
    }

    // Covers: 26.2 (the agent's version is one the agent supports, or the gateway does not build)
    @Test
    void anAgentVersionTheAgentDoesNotSupport_refusesToBuild() {
        assertThatThrownBy(() -> gateway(Set.of("2026-04-08"), "2026-01-23"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("unsupported UCP version 2026-01-23");
    }

    // Covers: 26.2, 8.1 (an answer at a version other than the one negotiated is unreadable: Uncertain for a create)
    @Test
    void anAnswerAtAnotherVersion_isUnreadable() {
        store.answer("create_checkout", "create_checkout.json");

        assertThat(gateway(Set.of("2026-08-25"), "2026-08-25").create(ONE_BOARD, CREATE_KEY))
                .isInstanceOf(CallOutcome.Uncertain.class);
    }

    // Covers: 26.2, 8.4 (the agent's default version, 2026-08-25, against an answer recorded at it). TODO(VERIFY):
    // no 2026-08-25 answer is recorded (the spike sent Shopify's 2026-04-08 sample agent profile); its shape is not
    // guessed. Stage 3: host the 2026-08-25 agent profile, record a create and a cancel at it as
    // create_checkout.2026-08-25.json and cancel_checkout.2026-08-25.json, and enable this test.
    @Test
    @Disabled("TODO(VERIFY): no 2026-08-25 answer recorded yet (H5 Stage 3, C10)")
    void aMerchantSpokenTo_in20260825_readsTheRecordedAnswers() {
        store.answer("create_checkout", "create_checkout.2026-08-25.json")
                .answer("cancel_checkout", "cancel_checkout.2026-08-25.json");
        McpMerchantGateway g = gateway(Set.of("2026-08-25"), "2026-08-25");

        assertThat(ok(g.create(ONE_BOARD, CREATE_KEY)).status()).isEqualTo(CheckoutStatus.INCOMPLETE);
        assertThat(ok(g.cancel(RECORDED, CANCEL_KEY)).status()).isEqualTo(CheckoutStatus.CANCELED);
    }
}
