package dev.ucplab.eval.interop;

import dev.ucplab.core.json.LenientJson;
import dev.ucplab.ucp.HttpCall;
import dev.ucplab.ucp.HttpReply;
import dev.ucplab.ucp.HttpTransport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * An {@link HttpTransport} decorator that records every call a run makes (H1 C9b), normalised: the path with ids replaced, the
 * status, and the JSON field paths with their types. It records no value, no header and no body text, so a payment token, an
 * {@code Authorization} header or an address can never be in a recording (I8). A call that fails with an exception is not recorded.
 */
public final class RecordingTransport implements HttpTransport {

    private static final Pattern ID_SEGMENT = Pattern.compile("^(?:[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}|chk_.*|cs_.*|[0-9a-f]{16,})$");

    private final HttpTransport delegate;
    private final List<WireStep> steps = new ArrayList<>();
    private final Map<String, Integer> ordinals = new HashMap<>();

    public RecordingTransport(HttpTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpReply send(HttpCall call) throws IOException {
        HttpReply reply = delegate.send(call);
        String body = call.body().map(b -> b.json()).orElse("");
        String path = normalisePath(call.uri().getPath());
        String op = op(call.method(), path);
        int n = ordinals.merge(op, 1, Integer::sum);
        Map<String, String> response = fields(new String(reply.body(), StandardCharsets.UTF_8));
        synchronized (steps) {
            steps.add(new WireStep(n == 1 ? op : op + "#" + n, path, reply.status(), fields(body), response,
                    errorCode(new String(reply.body(), StandardCharsets.UTF_8))));
        }
        return reply;
    }

    /** The calls so far, in order. */
    public List<WireStep> steps() {
        synchronized (steps) {
            return List.copyOf(steps);
        }
    }

    /**
     * The path relative to the endpoint the profile declared: everything before {@code /checkout-sessions} is the endpoint's
     * own prefix ({@code /ucp/v1} at the lab, nothing at the sample), so the two merchants' paths compare equal. A path
     * without it (discovery) is kept.
     */
    static String normalisePath(String path) {
        int at = path.indexOf("/checkout-sessions");
        if (at > 0) {
            path = path.substring(at);
        }
        StringBuilder out = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            out.append('/').append(ID_SEGMENT.matcher(seg).matches() ? "{id}" : seg);
        }
        return out.toString();
    }

    private static String op(String method, String path) {
        if (path.endsWith("/.well-known/ucp")) {
            return "discover";
        }
        if (path.endsWith("/complete")) {
            return "complete";
        }
        if (path.endsWith("/cancel")) {
            return "cancel";
        }
        return switch (method) {
            case "POST" -> "create";
            case "PUT" -> "update";
            case "GET" -> "get";
            default -> method.toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** JSON field paths with the type of each ({@code a.b[].c: string}); a body that is not a JSON object has none. */
    static Map<String, String> fields(String json) {
        Map<String, String> out = new TreeMap<>();
        try {
            if (json == null || json.isBlank()) {
                return out;
            }
            walk("", LenientJson.readObject(json), out);
        } catch (RuntimeException e) {
            // not JSON: no fields
        }
        return out;
    }

    private static void walk(String path, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> map) {
            if (!path.isEmpty()) {
                out.merge(path, "object", RecordingTransport::merge);
            }
            if (!isOpaque(path)) {
                map.forEach((k, v) -> walk(path.isEmpty() ? String.valueOf(k) : path + "." + k, v, out));
            }
        } else if (value instanceof List<?> list) {
            out.merge(path, "array", RecordingTransport::merge);
            if (!isOpaque(path)) {
                list.forEach(v -> walk(path + "[]", v, out));
            }
        } else {
            String type = value == null ? "null" : value instanceof Number ? "number" : value instanceof Boolean ? "boolean" : "string";
            out.merge(path, type, RecordingTransport::merge);
        }
    }

    /**
     * A merchant's own description of its payment handlers and capabilities ({@code ucp.payment_handlers.<name>},
     * {@code ucp.capabilities.<name>}) is recorded by name only: its internals are configuration, not the checkout wire.
     */
    private static boolean isOpaque(String path) {
        return path.startsWith("ucp.payment_handlers.") || path.startsWith("ucp.capabilities.");
    }

    private static String merge(String a, String b) {
        return a.equals(b) ? a : "mixed";
    }

    private static String errorCode(String body) {
        try {
            Map<String, Object> root = LenientJson.readObject(body);
            if (root.get("code") instanceof String c) {
                return c;
            }
            if (root.get("messages") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m
                    && "error".equals(m.get("type")) && m.get("code") instanceof String c) {
                return c;
            }
        } catch (RuntimeException e) {
            // no code
        }
        return "";
    }
}
