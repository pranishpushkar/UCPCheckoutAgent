package dev.ucplab.ucp.mcp;

import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.ucp.HttpCall;
import dev.ucplab.ucp.HttpReply;
import dev.ucplab.ucp.HttpTransport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * A scripted Shopify store for the MCP gateway's tests (H5 C6): a GET is answered with the profile, a POST by the
 * answers queued for its tool, each rewritten to the request's JSON-RPC id (the recordings carry the spike's). Every call
 * is recorded with its headers and body. Nothing leaves the JVM.
 */
public final class McpStore implements HttpTransport {

    /** What the store was sent. */
    public record Sent(String method, String uri, Map<String, String> headers, String body) {

        /** The JSON-RPC tool, or empty for a GET. */
        public String tool() {
            if (body.isEmpty()) {
                return "";
            }
            Map<?, ?> params = (Map<?, ?>) CanonicalJson.readObject(body).get("params");
            return (String) params.get("name");
        }

        /** {@code params.arguments} of a tool call. */
        public Map<?, ?> arguments() {
            Map<?, ?> params = (Map<?, ?>) CanonicalJson.readObject(body).get("params");
            return (Map<?, ?>) params.get("arguments");
        }

        /** A header by name, any case; empty string if absent. */
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue).findFirst().orElse("");
        }
    }

    private record Answer(int status, Map<String, List<String>> headers, UnaryOperator<Object> body) {}

    private final List<Sent> sent = new ArrayList<>();
    private final Map<String, Deque<Answer>> answers = new LinkedHashMap<>();
    private HttpReply profile = reply(200, fixture("store_profile.json"));

    public static String fixture(String name) {
        try (InputStream in = McpStore.class.getResourceAsStream("/shopify/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static HttpReply reply(int status, String body) {
        return new HttpReply(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    /** The profile a GET is answered with. */
    public McpStore profile(String json) {
        profile = reply(200, json);
        return this;
    }

    /** Every GET from now on is answered with {@code status} and {@code body}. */
    public McpStore profile(int status, String body) {
        profile = reply(status, body);
        return this;
    }

    /** The next call of {@code tool} is answered with the recorded {@code fixture}, changed by {@code edit} first. */
    public McpStore answer(String tool, String fixture, UnaryOperator<String> edit) {
        String text = edit.apply(fixture(fixture));
        return queue(tool, 200, id -> {
            Map<String, Object> root = new LinkedHashMap<>(LenientJson.readObject(text));
            root.put("id", id);
            return McpJson.write(root);
        });
    }

    public McpStore answer(String tool, String fixture) {
        return answer(tool, fixture, t -> t);
    }

    /** The next call of {@code tool} gets an HTTP 200 holding a JSON-RPC error. */
    public McpStore error(String tool, long code, String message) {
        return queue(tool, 200, id -> "{\"jsonrpc\":\"2.0\",\"id\":" + McpJson.write(id) + ",\"error\":{\"code\":" + code
                + ",\"message\":" + McpJson.write(message) + "}}");
    }

    /** The next call of {@code tool} gets {@code status} with {@code body} as it is. */
    public McpStore raw(String tool, int status, String body) {
        return queue(tool, status, Map.of(), id -> body);
    }

    /** As {@link #raw(String, int, String)}, with one reply header. */
    public McpStore raw(String tool, int status, String body, String header, String value) {
        return queue(tool, status, Map.of(header.toLowerCase(Locale.ROOT), List.of(value)), id -> body);
    }

    private McpStore queue(String tool, int status, UnaryOperator<Object> body) {
        return queue(tool, status, Map.of(), body);
    }

    private McpStore queue(String tool, int status, Map<String, List<String>> headers, UnaryOperator<Object> body) {
        answers.computeIfAbsent(tool, t -> new ArrayDeque<>()).add(new Answer(status, headers, body));
        return this;
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    /** The tool calls only, without discovery's GETs. */
    public List<Sent> toolCalls() {
        return sent.stream().filter(s -> s.method().equals("POST")).toList();
    }

    @Override
    public HttpReply send(HttpCall call) throws IOException {
        String body = call.body().map(b -> b.json()).orElse("");
        Sent s = new Sent(call.method(), call.uri().toString(), Map.copyOf(call.headers()), body);
        sent.add(s);
        if (call.method().equals("GET")) {
            return profile;
        }
        Deque<Answer> queue = answers.get(s.tool());
        if (queue == null || queue.isEmpty()) {
            throw new IOException("the scripted store has no answer for " + s.tool());
        }
        Answer a = queue.poll();
        Object id = CanonicalJson.readObject(body).get("id");
        return new HttpReply(a.status(), a.headers(), ((String) a.body().apply(id)).getBytes(StandardCharsets.UTF_8));
    }
}
