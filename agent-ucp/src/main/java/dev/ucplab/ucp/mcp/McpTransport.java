package dev.ucplab.ucp.mcp;

import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.ucp.HttpCall;
import dev.ucplab.ucp.HttpReply;
import dev.ucplab.ucp.HttpTransport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP over the existing HTTP stack (8.4): a decorator of {@link HttpTransport} that turns each JSON-RPC answer into the
 * reply the REST path already reads, so the retry executor, the classifier of 8.1, the breaker and the rate limiter run
 * unchanged and there is no second retry layer.
 *
 * <ul>
 *   <li>Outgoing: the REST {@code UCP-Agent} and {@code Idempotency-Key} headers are dropped (their values travel in
 *       {@code meta}, {@link McpEnvelope}); {@code Request-Id} and the frozen body pass as they are.
 *   <li>A non-2xx status keeps its status and headers ({@code Retry-After} included); its body becomes only
 *       {@code {"code":...}} from the JSON-RPC error, so 8.1's table applies and no merchant text passes.
 *   <li>A 200 or 201 holding a JSON-RPC {@code error} becomes the status its code stands for, with the same code body.
 *   <li>A 200 or 201 holding a {@code result} becomes a 200 whose body is {@code result.structuredContent}. A result
 *       with {@code isError: true} or no {@code structuredContent}, one whose {@code structuredContent.ucp.version} is
 *       not the version this transport speaks (26.2), a different {@code id}, neither or both of
 *       {@code result} and {@code error}, or a body that is not JSON become an empty body, which every parser refuses
 *       (8.1, last row).
 * </ul>
 * {@code result.content[].text} is never read: the answer is parsed once and only {@code structuredContent} is written
 * back. The transport never logs and never puts reply text in an exception.
 */
public final class McpTransport implements HttpTransport {

    /** The status an unknown JSON-RPC error stands for: one 8.1 does not list (Uncertain for mutating calls, ProtocolError for get), and not a 5xx outage. */
    static final int UNLISTED = 202;
    private static final Set<String> REST_ONLY_HEADERS = Set.of("ucp-agent", "idempotency-key");
    private static final Pattern BARE_CODE = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final byte[] UNREADABLE = new byte[0];

    private final HttpTransport next;
    private final String version;

    /** @param version the UCP version negotiated for the merchant (26.2): every answer must be at it */
    public McpTransport(HttpTransport next, String version) {
        this.next = Objects.requireNonNull(next, "next");
        this.version = Objects.requireNonNull(version, "version");
    }

    @Override
    public HttpReply send(HttpCall call) throws IOException {
        Objects.requireNonNull(call, "call");
        Object rpcId = call.body().map(b -> CanonicalJson.readObject(b.json()).get("id"))
                .orElseThrow(() -> new IllegalArgumentException("an MCP call has a JSON-RPC body"));
        Map<String, String> headers = new LinkedHashMap<>();
        call.headers().forEach((k, v) -> {
            if (!REST_ONLY_HEADERS.contains(k.toLowerCase(Locale.ROOT))) {
                headers.put(k, v);
            }
        });
        HttpReply reply = next.send(new HttpCall(call.method(), call.uri(), headers, call.body(), call.timeout()));
        return unwrap(rpcId, version, reply);
    }

    static HttpReply unwrap(Object rpcId, String version, HttpReply reply) {
        Map<String, Object> root;
        try {
            root = LenientJson.readObject(new String(reply.body(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException notJson) {
            root = Map.of();
        }
        int status = reply.status();
        Object error = root.get("error");
        if (status != 200 && status != 201) {
            return new HttpReply(status, reply.headers(), error instanceof Map<?, ?> e ? codeBody(e) : UNREADABLE);
        }
        Object result = root.get("result");
        if (!Objects.equals(root.get("id"), rpcId) || (error == null) == (result == null)) {
            return new HttpReply(200, reply.headers(), UNREADABLE);
        }
        if (error instanceof Map<?, ?> e) {
            return new HttpReply(statusOf(e), reply.headers(), codeBody(e));
        }
        if (result instanceof Map<?, ?> r && !Boolean.TRUE.equals(r.get("isError"))
                && r.get("structuredContent") instanceof Map<?, ?> content
                && content.get("ucp") instanceof Map<?, ?> ucp && version.equals(ucp.get("version"))) {
            return new HttpReply(200, reply.headers(), McpJson.write(content).getBytes(StandardCharsets.UTF_8));
        }
        // TODO(VERIFY 8): how the store answers a business refusal (isError, a JSON-RPC error, or messages in
        // structuredContent) is not yet recorded; until it is, a tool error reads as a missing required field (8.4).
        return new HttpReply(200, reply.headers(), UNREADABLE);
    }

    /** The status a JSON-RPC error in a 200 stands for (8.4). */
    private static int statusOf(Map<?, ?> error) {
        long code = error.get("code") instanceof Long c ? c : 0;
        if (code == -32700 || code == -32600 || code == -32601 || code == -32602) {
            return 400;
        }
        if (code <= -32000 && code >= -32099 && "AuthenticationFailed".equals(error.get("message"))) {
            return 401;
        }
        if (code == -32603) {
            return 500;
        }
        return UNLISTED;
    }

    /** {@code {"code":...}}: the message if it is a bare identifier of at most 64 characters, lower-cased, else {@code jsonrpc_<code>}. */
    private static byte[] codeBody(Map<?, ?> error) {
        String code = error.get("message") instanceof String m && BARE_CODE.matcher(m).matches()
                ? m.toLowerCase(Locale.ROOT)
                : "jsonrpc_" + (error.get("code") instanceof Long c ? c.toString() : "unknown");
        return CanonicalJson.write(Map.of("code", code)).getBytes(StandardCharsets.UTF_8);
    }
}
