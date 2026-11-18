package dev.ucplab.ucp.mcp;

import static dev.ucplab.ucp.mcp.McpTransportTest.PROFILE;
import static dev.ucplab.ucp.mcp.McpTransportTest.RPC_ID;
import static dev.ucplab.ucp.mcp.McpTransportTest.VERSION;
import static dev.ucplab.ucp.mcp.McpTransportTest.reply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.json.LenientJson;
import dev.ucplab.ucp.HttpCall;
import dev.ucplab.ucp.HttpReply;
import dev.ucplab.ucp.HttpTransport;
import dev.ucplab.ucp.auth.ClientCredentials;
import dev.ucplab.ucp.auth.TokenProvider;
import dev.ucplab.ucp.registry.BuyerIp;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * H5 C6 ★: the MCP layer's decisions one by one (8.4, 26.4, I8): the envelope's tool check, how a JSON-RPC answer is
 * unwrapped, the status an error code stands for, and the headers. Each test names the mutants it kills by method and
 * line.
 */
class McpMutationTest {

    private static final String CONTENT = "{\"id\":\"chk_1\",\"ucp\":{\"version\":\"2026-04-08\"}}";

    private static HttpReply unwrap(int status, String body) {
        return McpTransport.unwrap(RPC_ID, VERSION, reply(status, body));
    }

    private static String text(HttpReply reply) {
        return new String(reply.body(), StandardCharsets.UTF_8);
    }

    private static String rpc(String members) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + RPC_ID + "\"" + members + "}";
    }

    private static int statusOf(long code, String message) {
        return unwrap(200, rpc(",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"}")).status();
    }

    // ---------------------------------------------------------------- the envelope

    // Covers: 8.4, N1 (a tool 8.4 does not name is refused even with no key, so it is never mistaken for a read).
    // Kills McpEnvelope.toolCall:46 RemoveConditional (the READ_TOOLS test)
    @Test
    void anUnknownTool_isRefused_withOrWithoutAKey() {
        assertThatThrownBy(() -> McpEnvelope.toolCall(() -> RPC_ID, "complete_checkout", Map.of(), PROFILE,
                Optional.empty())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an MCP tool");
    }

    // Covers: 8.4 (every MCP call is a JSON-RPC body; one without is refused before anything is sent). Kills
    // McpTransport.lambda$send$1:54 NullReturnVals (NO_COVERAGE)
    @Test
    void aCallWithoutABody_isRefused_andNotSent() {
        Recording store = new Recording(200);

        assertThatThrownBy(() -> new McpTransport(store, VERSION).send(call())).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an MCP call has a JSON-RPC body");
        assertThat(store.calls).isEmpty();
    }

    // ---------------------------------------------------------------- unwrap

    // Covers: 8.4 (a 201 is read as a 200: its structuredContent is the body). Kills McpTransport.unwrap:74
    // RemoveConditional (the `status != 201` operand)
    @Test
    void a201_isReadAsA200() {
        HttpReply r = unwrap(201, rpc(",\"result\":{\"structuredContent\":" + CONTENT + "}"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(text(r)).isEqualTo(CONTENT);
    }

    // Covers: 8.4, 8.1, I8 (a non-2xx keeps its status; its body is the error's code when there is a JSON-RPC error,
    // and empty otherwise: no merchant text passes). Kills McpTransport.unwrap:74 (the `status != 200` operand) and :75
    // RemoveConditional
    @Test
    void aNon2xx_keepsItsStatus_andOnlyTheCode() {
        HttpReply withError = unwrap(400, rpc(",\"error\":{\"code\":-32602,\"message\":\"bad_input\"}"));
        assertThat(withError.status()).isEqualTo(400);
        assertThat(LenientJson.readObject(text(withError))).isEqualTo(Map.of("code", "bad_input"));

        HttpReply plain = unwrap(400, "<html>merchant text</html>");
        assertThat(plain.status()).isEqualTo(400);
        assertThat(plain.body()).isEmpty();

        HttpReply result = unwrap(503, rpc(",\"result\":{\"structuredContent\":" + CONTENT + "}"));
        assertThat(result.status()).isEqualTo(503);
        assertThat(result.body()).as("a result in a non-2xx is not read").isEmpty();
    }

    // Covers: 8.4, 8.1 (an answer to another id, or with both or neither of result and error, is unreadable). Kills
    // McpTransport.unwrap:78 RemoveConditional
    @Test
    void anAnswerToAnotherId_orWithBothOrNeither_isUnreadable() {
        String content = ",\"result\":{\"structuredContent\":" + CONTENT + "}";
        assertThat(unwrap(200, "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-2\"" + content + "}").body()).isEmpty();
        assertThat(unwrap(200, rpc(content + ",\"error\":{\"code\":-32602,\"message\":\"x\"}")).body()).isEmpty();
        assertThat(unwrap(200, rpc("")).body()).isEmpty();
        assertThat(unwrap(200, rpc(content)).body()).isNotEmpty();
    }

    // Covers: 8.4, 8.1 (only a result object that is not an error and holds a structuredContent object is read). Kills
    // McpTransport.unwrap:84 and :85 RemoveConditional
    @Test
    void aResult_isReadOnlyIfItIsAnObject_notAnError_withStructuredContent() {
        assertThat(unwrap(200, rpc(",\"result\":5")).body()).isEmpty();
        assertThat(unwrap(200, rpc(",\"result\":{\"isError\":true,\"structuredContent\":" + CONTENT + "}")).body())
                .isEmpty();
        assertThat(unwrap(200, rpc(",\"result\":{\"content\":[]}")).body()).isEmpty();
        assertThat(unwrap(200, rpc(",\"result\":{\"structuredContent\":5}")).body()).isEmpty();
        assertThat(text(unwrap(200, rpc(",\"result\":{\"isError\":false,\"structuredContent\":" + CONTENT + "}"))))
                .isEqualTo(CONTENT);
    }

    // Covers: 26.2, 8.1 (an answer is read only at the version this transport speaks: one at another version, one
    // with no ucp object, or with a ucp that is not an object, is unreadable). H5 C10
    @Test
    void aResult_isReadOnlyAtTheNegotiatedVersion() {
        assertThat(unwrap(200, rpc(",\"result\":{\"structuredContent\":{\"id\":\"chk_1\",\"ucp\":{\"version\":\"2026-08-25\"}}}"))
                .body()).isEmpty();
        assertThat(unwrap(200, rpc(",\"result\":{\"structuredContent\":{\"id\":\"chk_1\"}}")).body()).isEmpty();
        assertThat(unwrap(200, rpc(",\"result\":{\"structuredContent\":{\"id\":\"chk_1\",\"ucp\":\"2026-04-08\"}}")).body())
                .isEmpty();
        assertThat(text(unwrap(200, rpc(",\"result\":{\"structuredContent\":" + CONTENT + "}")))).isEqualTo(CONTENT);
    }

    // ---------------------------------------------------------------- statusOf

    // Covers: 8.4, 26.4 (AuthenticationFailed is a 401 only as a server error, -32000 to -32099 inclusive; outside the
    // range, and any code 8.4 does not list, it is the unlisted status). Kills McpTransport.statusOf:99
    // ConditionalsBoundary and RemoveConditional (both bounds) and :105 PrimitiveReturns
    @Test
    void authenticationFailed_isA401_onlyInTheServerErrorRange() {
        assertThat(statusOf(-32000, "AuthenticationFailed")).isEqualTo(401);
        assertThat(statusOf(-32099, "AuthenticationFailed")).isEqualTo(401);
        assertThat(statusOf(-31999, "AuthenticationFailed")).isEqualTo(McpTransport.UNLISTED);
        assertThat(statusOf(-32100, "AuthenticationFailed")).isEqualTo(McpTransport.UNLISTED);
        assertThat(statusOf(-32050, "Other")).isEqualTo(McpTransport.UNLISTED);
        assertThat(statusOf(-5, "x")).isEqualTo(McpTransport.UNLISTED);
    }

    // ---------------------------------------------------------------- StoreHeaders

    /** Records what reaches it and answers with a fixed status. */
    private static final class Recording implements HttpTransport {
        final List<HttpCall> calls = new ArrayList<>();
        final int status;

        Recording(int status) {
            this.status = status;
        }

        @Override
        public HttpReply send(HttpCall call) {
            calls.add(call);
            return reply(status, "{}");
        }
    }

    private static TokenProvider tokens(HttpTransport tokenEndpoint) {
        return new TokenProvider(URI.create("https://api.shopify.com/auth/access_token"),
                new ClientCredentials("client-1", "secret-1"), tokenEndpoint, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofSeconds(5));
    }

    private static HttpCall call() {
        return new HttpCall("GET", URI.create("https://example-dev.myshopify.com/.well-known/ucp"), Map.of(),
                Optional.empty(), Duration.ofSeconds(5));
    }

    // Covers: 26.4, I8 (with no token to be had, the attempt is not sent: the provider's fixed reason, no value). Kills
    // StoreHeaders.send:55 RemoveConditional
    @Test
    void storeHeaders_withNoToken_sendNothing() {
        Recording store = new Recording(200);
        StoreHeaders h = new StoreHeaders(store, Optional.of(tokens(new Recording(500))), Optional.empty());

        assertThatThrownBy(() -> h.send(call())).isInstanceOf(IOException.class).hasMessageStartingWith("no access token: ")
                .hasMessageNotContaining("secret-1");
        assertThat(store.calls).isEmpty();
    }

    // Covers: 8.5, 26.4 (a 401 to a call that carried no token, as discovery's, is returned as it is: there is no token to
    // drop). Kills StoreHeaders.send:66 RemoveConditional (the `used.isPresent()` operand)
    @Test
    void storeHeaders_a401WithoutAToken_isReturned() throws IOException {
        StoreHeaders h = new StoreHeaders(new Recording(401), Optional.empty(), Optional.empty());

        assertThat(h.send(call()).status()).isEqualTo(401);
    }

    // Covers: I8 (the headers' text says whether there is a token and a buyer IP, never their values). Kills
    // StoreHeaders.toString:74
    @Test
    void storeHeaders_toString_showsNoValue() {
        StoreHeaders h = new StoreHeaders(new Recording(200), Optional.empty(), Optional.of(new BuyerIp("203.0.113.77")));

        assertThat(h).hasToString("StoreHeaders[token=false, buyerIp=true]");
    }
}
