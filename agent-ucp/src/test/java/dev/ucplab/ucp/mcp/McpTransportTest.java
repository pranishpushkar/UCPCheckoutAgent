package dev.ucplab.ucp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.ucp.ApiCall;
import dev.ucplab.ucp.FailureClassifier;
import dev.ucplab.ucp.Gate;
import dev.ucplab.ucp.HttpCall;
import dev.ucplab.ucp.HttpReply;
import dev.ucplab.ucp.HttpTransport;
import dev.ucplab.ucp.RetryExecutor;
import dev.ucplab.ucp.UcpConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The MCP client (8.4, H5 C4): the envelope, and every row of 8.1 as it applies to a JSON-RPC answer. */
class McpTransportTest {

    static final FailureClassifier CLASSIFIER = new FailureClassifier(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    static final List<AttemptOp> MUTATING = List.of(AttemptOp.CREATE, AttemptOp.UPDATE, AttemptOp.COMPLETE,
            AttemptOp.CANCEL);
    static final List<AttemptOp> ALL_OPS = List.of(AttemptOp.CREATE, AttemptOp.UPDATE, AttemptOp.COMPLETE,
            AttemptOp.CANCEL, AttemptOp.GET);
    static final URI PROFILE = URI.create("https://agent.example/.well-known/ucp/agent.json");
    static final URI ENDPOINT = URI.create("https://example-dev.myshopify.com/api/ucp/mcp");
    static final IdempotencyKey KEY = new IdempotencyKey("11111111-2222-8333-8444-555555555555");
    static final String RPC_ID = "rpc-1";

    static String fixture(String name) {
        try (InputStream in = McpTransportTest.class.getResourceAsStream("/shopify/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static HttpReply reply(int status, String body, String... headers) {
        Map<String, List<String>> h = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            h.put(headers[i].toLowerCase(), List.of(headers[i + 1]));
        }
        return new HttpReply(status, h, body.getBytes(StandardCharsets.UTF_8));
    }

    /** A JSON-RPC answer to {@link #RPC_ID} with a {@code result}. */
    static String result(String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + RPC_ID + "\",\"result\":" + resultJson + "}";
    }

    /** A JSON-RPC error answer to {@link #RPC_ID}. */
    static String error(long code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + RPC_ID + "\",\"error\":{\"code\":" + code + ",\"message\":\"" + message
                + "\",\"data\":\"merchant text\"}}";
    }

    /** The recorded create, rewritten to answer {@link #RPC_ID}. */
    static String createAnswer() {
        Map<String, Object> answer = new LinkedHashMap<>(LenientJson.readObject(fixture("create_checkout.json")));
        answer.put("id", RPC_ID);
        return McpJson.write(answer);
    }

    static CallOutcome<CheckoutView> classify(AttemptOp op, HttpReply reply) {
        return CLASSIFIER.classify(op, McpTransport.unwrap(RPC_ID, reply));
    }

    static String code(HttpReply reply) {
        return (String) LenientJson.readObject(new String(McpTransport.unwrap(RPC_ID, reply).body(),
                StandardCharsets.UTF_8)).get("code");
    }

    /** Plays back replies and failures, and records every call. */
    static final class Scripted implements HttpTransport {
        final Deque<Object> script = new ArrayDeque<>();
        final List<HttpCall> calls = new ArrayList<>();

        Scripted then(Object... steps) {
            script.addAll(List.of(steps));
            return this;
        }

        @Override
        public HttpReply send(HttpCall call) throws IOException {
            calls.add(call);
            Object step = script.removeFirst();
            if (step instanceof IOException e) {
                throw e;
            }
            return (HttpReply) step;
        }
    }

    static RetryExecutor executor(HttpTransport inner, List<Duration> sleeps) {
        return new RetryExecutor(UcpConfig.defaults("profile=\"" + PROFILE + "\""), new McpTransport(inner), CLASSIFIER,
                Gate.NONE, sleeps::add, new SplittableRandom(42), new SequentialIdGenerator("req-"));
    }

    static ApiCall call(AttemptOp op, FrozenBody body) {
        return new ApiCall(op, "POST", ENDPOINT, op == AttemptOp.GET ? Optional.empty() : Optional.of(KEY),
                Optional.of(body), false);
    }

    static FrozenBody createBody(IdGenerator ids) {
        return McpEnvelope.toolCall(ids, "create_checkout", Map.of("checkout", Map.of("currency", "USD")), PROFILE,
                Optional.of(KEY));
    }

    // ---------------------------------------------------------------- the envelope

    // Covers: 8.4 (a tools/call in canonical JSON: the agent's profile in meta["ucp-agent"].profile, the key in
    // meta["idempotency-key"], the id from the IdGenerator)
    @Test
    void envelope_mutatingTool_isCanonicalWithProfileAndKeyInMeta() {
        FrozenBody body = createBody(() -> RPC_ID);

        assertThat(body.json()).isEqualTo("{\"id\":\"rpc-1\",\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":"
                + "{\"arguments\":{\"checkout\":{\"currency\":\"USD\"},\"meta\":{\"idempotency-key\":\"" + KEY.value()
                + "\",\"ucp-agent\":{\"profile\":\"" + PROFILE + "\"}}},\"name\":\"create_checkout\"}}");
    }

    // Covers: 8.4 (a read carries no idempotency key)
    @Test
    void envelope_read_carriesNoKey() {
        FrozenBody body = McpEnvelope.toolCall(() -> RPC_ID, "get_checkout", Map.of("id", "gid://shopify/Checkout/x"),
                PROFILE, Optional.empty());

        assertThat(body.json()).doesNotContain("idempotency-key").contains("\"ucp-agent\":{\"profile\":\"" + PROFILE);
    }

    // Covers: 8.4, I7 (every mutating tool needs a key, a read may not have one, only 8.4's tools are called, and the
    // caller cannot supply its own meta)
    @Test
    void envelope_refuses_missingOrStrayKeys_unknownTools_andCallerMeta() {
        for (String tool : McpEnvelope.MUTATING_TOOLS) {
            assertThatThrownBy(() -> McpEnvelope.toolCall(() -> RPC_ID, tool, Map.of(), PROFILE, Optional.empty()))
                    .as(tool).isInstanceOf(IllegalArgumentException.class);
        }
        for (String tool : McpEnvelope.READ_TOOLS) {
            assertThatThrownBy(() -> McpEnvelope.toolCall(() -> RPC_ID, tool, Map.of(), PROFILE, Optional.of(KEY)))
                    .as(tool).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> McpEnvelope.toolCall(() -> RPC_ID, "complete_checkout", Map.of(), PROFILE,
                Optional.of(KEY))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McpEnvelope.toolCall(() -> RPC_ID, "create_checkout", Map.of("meta", Map.of()),
                PROFILE, Optional.of(KEY))).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 8.4, I7 (owner's addition 2: two retries of one call send identical body bytes, the same JSON-RPC id, and
    // a new Request-Id each)
    @Test
    void retries_sendIdenticalBodyBytes_withANewRequestIdEach() {
        Scripted inner = new Scripted().then(new HttpTimeoutException("timed out"), new HttpTimeoutException("again"),
                reply(200, createAnswer()));
        FrozenBody body = createBody(() -> RPC_ID);

        CallOutcome<CheckoutView> outcome = executor(inner, new ArrayList<>()).execute(call(AttemptOp.CREATE, body));

        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        assertThat(inner.calls).hasSize(3);
        assertThat(inner.calls).allSatisfy(c -> assertThat(c.body().orElseThrow().bytes()).isEqualTo(body.bytes()));
        assertThat(inner.calls.stream().map(c -> c.headers().get("Request-Id")).distinct()).hasSize(3);
    }

    // Covers: 8.4 (over MCP the agent and the key travel in meta: the REST UCP-Agent and Idempotency-Key headers are not
    // sent; Request-Id and Content-Type are)
    @Test
    void outgoing_dropsTheRestHeaders_keepsRequestId() {
        Scripted inner = new Scripted().then(reply(200, createAnswer()));

        executor(inner, new ArrayList<>()).execute(call(AttemptOp.CREATE, createBody(() -> RPC_ID)));

        assertThat(inner.calls.get(0).headers()).containsKeys("Request-Id", "Content-Type")
                .doesNotContainKeys("UCP-Agent", "Idempotency-Key");
    }

    // ---------------------------------------------------------------- 8.1 row by row, over MCP

    // Covers: 8.1 (HTTP 200 with a valid body: Ok), 8.4 (read from result.structuredContent), H5 F (the recorded create
    // and cancel)
    @Test
    void row_resultWithStructuredContent_isOk_onEveryOp() {
        Map<String, Object> cancel = new LinkedHashMap<>(LenientJson.readObject(fixture("cancel_checkout.json")));
        cancel.put("id", RPC_ID);
        for (AttemptOp op : ALL_OPS) {
            assertThat(classify(op, reply(200, createAnswer()))).as(op.name()).isInstanceOfSatisfying(CallOutcome.Ok.class,
                    ok -> assertThat(((CheckoutView) ok.value()).status()).isEqualTo(CheckoutStatus.INCOMPLETE));
            assertThat(classify(op, reply(200, McpJson.write(cancel)))).as(op.name())
                    .isInstanceOfSatisfying(CallOutcome.Ok.class,
                            ok -> assertThat(((CheckoutView) ok.value()).status()).isEqualTo(CheckoutStatus.CANCELED));
        }
    }

    // Covers: 8.4 (unknown fields of the envelope, the result and the payload, and unknown capabilities, are ignored)
    @Test
    void row_unknownFieldsAndCapabilities_areIgnored() {
        String answer = createAnswer().replaceFirst("\\{", "{\"x_extra\":{\"a\":1},")
                .replace("\"isError\":", "\"_meta\":{\"x\":null},\"isError\":")
                .replace("\"capabilities\":{", "\"capabilities\":{\"dev.example.unknown\":[{\"version\":\"2099-01-01\"}],");

        for (AttemptOp op : ALL_OPS) {
            assertThat(classify(op, reply(200, answer))).as(op.name()).isInstanceOf(CallOutcome.Ok.class);
        }
    }

    // Covers: 8.4 (a JSON-RPC error in a 200 with a standard request-error code is a 400: ProtocolError, not retried)
    @Test
    void row_requestErrorCodes_inA200_areProtocolError400() {
        for (long code : new long[] {-32700, -32600, -32601, -32602}) {
            for (AttemptOp op : ALL_OPS) {
                assertThat(classify(op, reply(200, error(code, "Invalid params")))).as(op + " " + code)
                        .isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                                e -> assertThat(e.httpStatus()).isEqualTo(400));
            }
        }
    }

    // Covers: 8.4, 8.1 (AuthenticationFailed in a 200 is a 401: ProtocolError with the code authenticationfailed)
    @Test
    void row_authenticationFailed_inA200_isProtocolError401() {
        for (AttemptOp op : ALL_OPS) {
            assertThat(classify(op, reply(200, error(-32000, "AuthenticationFailed")))).as(op.name())
                    .isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
                        assertThat(e.httpStatus()).isEqualTo(401);
                        assertThat(e.code()).isEqualTo("authenticationfailed");
                    });
        }
    }

    // Covers: 8.4, 8.1 (the recorded 422 with AuthenticationFailed: the HTTP status decides, a ProtocolError not retried)
    @Test
    void row_recorded422_isProtocolError_notRetried() {
        Map<String, Object> recorded = LenientJson.readObject(fixture("catalog_search_store.error.json"));
        String body = ((String) recorded.get("body")).replace("\"id\": 1", "\"id\": \"" + RPC_ID + "\"");
        for (AttemptOp op : ALL_OPS) {
            assertThat(classify(op, reply(422, body))).as(op.name())
                    .isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
                        assertThat(e.httpStatus()).isEqualTo(422);
                        assertThat(e.code()).isEqualTo("authenticationfailed");
                    });
        }
        Scripted inner = new Scripted().then(reply(422, body));
        executor(inner, new ArrayList<>()).execute(call(AttemptOp.CREATE, createBody(() -> RPC_ID)));
        assertThat(inner.calls).as("not retried").hasSize(1);
    }

    // Covers: 8.4 (internal error is a 500: Uncertain on every op; a get is retried, 8.2)
    @Test
    void row_internalError_isUncertain_andAGetIsRetried() {
        for (AttemptOp op : ALL_OPS) {
            assertThat(classify(op, reply(200, error(-32603, "Internal error")))).as(op.name())
                    .isInstanceOf(CallOutcome.Uncertain.class);
        }
        Scripted inner = new Scripted();
        for (int i = 0; i < 4; i++) {
            inner.then(reply(200, error(-32603, "Internal error")));
        }
        FrozenBody get = McpEnvelope.toolCall(() -> RPC_ID, "get_checkout", Map.of("id", "x"), PROFILE, Optional.empty());
        assertThat(executor(inner, new ArrayList<>()).execute(call(AttemptOp.GET, get)))
                .isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(inner.calls).hasSize(4);
    }

    // Covers: 8.4 (any other JSON-RPC error, a server error with another message included, is an unlisted status:
    // Uncertain for mutating calls, ProtocolError for get)
    @Test
    void row_otherErrors_areUncertainForMutating_protocolErrorForGet() {
        List<String> answers = List.of(error(-32000, "Throttled"), error(-32050, "AuthenticationFailed2"),
                error(1, "Custom"), "{\"jsonrpc\":\"2.0\",\"id\":\"" + RPC_ID + "\",\"error\":{}}");
        for (String answer : answers) {
            for (AttemptOp op : MUTATING) {
                assertThat(classify(op, reply(200, answer))).as(op + " " + answer).isInstanceOf(CallOutcome.Uncertain.class);
            }
            assertThat(classify(AttemptOp.GET, reply(200, answer))).as(answer).isInstanceOf(CallOutcome.ProtocolError.class);
        }
    }

    // Covers: 8.4, 8.1 (a non-2xx keeps its status and headers: 429 with Retry-After is a ProtocolError with retryAfter,
    // except complete, which is Uncertain; 500 and 502 are Uncertain)
    @Test
    void row_non2xx_isClassifiedByItsStatus() {
        HttpReply throttled = reply(429, error(-32000, "Throttled"), "Retry-After", "1");
        for (AttemptOp op : List.of(AttemptOp.CREATE, AttemptOp.UPDATE, AttemptOp.CANCEL, AttemptOp.GET)) {
            assertThat(classify(op, throttled)).as(op.name()).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
                assertThat(e.httpStatus()).isEqualTo(429);
                assertThat(e.retryAfter()).contains(Duration.ofSeconds(1));
            });
        }
        assertThat(classify(AttemptOp.COMPLETE, throttled)).isInstanceOf(CallOutcome.Uncertain.class);
        for (int status : new int[] {500, 502}) {
            for (AttemptOp op : MUTATING) {
                assertThat(classify(op, reply(status, error(-32603, "Internal error")))).as(op + " " + status)
                        .isInstanceOf(CallOutcome.Uncertain.class);
            }
        }
    }

    // Covers: 8.4, 8.1 last row (isError, no structuredContent, another id, neither or both of result and error, not
    // JSON: a body the client cannot read: Uncertain for mutating calls, ProtocolError for get)
    @Test
    void row_unreadableAnswers_areUncertainForMutating_protocolErrorForGet() {
        String created = createAnswer();
        // A checkout in content[].text alone: readable there, but the client never reads it (8.4).
        Map<String, Object> structured = LenientJson.readObject(fixture("create_checkout.json"));
        @SuppressWarnings("unchecked")
        Object checkout = ((Map<String, Object>) structured.get("result")).get("structuredContent");
        String textOnly = result(McpJson.write(Map.of("content", List.of(Map.of("type", "text",
                "text", McpJson.write(checkout))), "isError", false)));
        assertThat(CLASSIFIER.classify(AttemptOp.CREATE, new HttpReply(200, Map.of(),
                McpJson.write(checkout).getBytes(StandardCharsets.UTF_8)))).as("that text is a valid checkout")
                .isInstanceOf(CallOutcome.Ok.class);
        List<String> answers = List.of(
                textOnly,
                created.replace("\"isError\":false", "\"isError\":true"),
                result("{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}],\"isError\":false}"),
                created.replace("\"id\":\"" + RPC_ID + "\"", "\"id\":\"rpc-2\""),
                "{\"jsonrpc\":\"2.0\",\"id\":\"" + RPC_ID + "\"}",
                result("{\"structuredContent\":{}}").replace("\"result\"", "\"error\":{\"code\":1},\"result\""),
                "not json",
                "[1]");
        for (String answer : answers) {
            for (AttemptOp op : MUTATING) {
                assertThat(classify(op, reply(200, answer))).as(op + " " + answer.substring(0, Math.min(60, answer.length())))
                        .isInstanceOf(CallOutcome.Uncertain.class);
            }
            assertThat(classify(AttemptOp.GET, reply(200, answer))).isInstanceOf(CallOutcome.ProtocolError.class);
        }
    }

    // Covers: 8.4 (owner's addition 1: error.message is merchant text, a code only as at most 64 characters of
    // [A-Za-z0-9_], lower-cased; otherwise jsonrpc_<code>)
    @Test
    void code_isTheMessage_onlyAsAShortBareIdentifier() {
        String sixtyFour = "A".repeat(64);
        assertThat(code(reply(200, error(-32000, "AuthenticationFailed")))).isEqualTo("authenticationfailed");
        assertThat(code(reply(200, error(-32000, sixtyFour)))).isEqualTo("a".repeat(64));
        assertThat(code(reply(200, error(-32000, sixtyFour + "B")))).isEqualTo("jsonrpc_-32000");
        assertThat(code(reply(200, error(-32602, "Invalid params")))).isEqualTo("jsonrpc_-32602");
        assertThat(code(reply(200, error(-32000, "bad-code")))).isEqualTo("jsonrpc_-32000");
        assertThat(code(reply(422, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":\"x\",\"message\":7}}")))
                .isEqualTo("jsonrpc_unknown");
    }
}
