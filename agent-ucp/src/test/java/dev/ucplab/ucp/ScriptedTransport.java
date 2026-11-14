package dev.ucplab.ucp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link HttpTransport} that answers a merchant's routes from recorded replies, whatever the path prefix the merchant
 * uses (the lab serves {@code /ucp/v1/checkout-sessions}, the sample {@code /checkout-sessions}). Each route plays its
 * replies in order and repeats the last. A route with no reply fails the test: a gateway must not make a call the
 * situation did not expect. Records every call.
 */
final class ScriptedTransport implements HttpTransport {

    enum Route { DISCOVERY, CREATE, UPDATE, COMPLETE, CANCEL, GET }

    private final Map<Route, Deque<HttpReply>> script = new EnumMap<>(Route.class);
    private final List<HttpCall> calls = new ArrayList<>();

    ScriptedTransport on(Route route, HttpReply... replies) {
        script.computeIfAbsent(route, r -> new ArrayDeque<>()).addAll(List.of(replies));
        return this;
    }

    /** Every route except discovery answers {@code reply}: a merchant that answers whatever it is asked with one body. */
    ScriptedTransport onEveryCall(HttpReply reply) {
        for (Route r : Route.values()) {
            if (r != Route.DISCOVERY) {
                on(r, reply);
            }
        }
        return this;
    }

    List<HttpCall> calls() {
        return calls;
    }

    static HttpReply reply(int status, String body) {
        return new HttpReply(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    static String text(HttpReply reply) {
        return new String(reply.body(), StandardCharsets.UTF_8);
    }

    private static Route classify(HttpCall call) {
        String path = call.uri().getPath();
        if (path.equals("/.well-known/ucp")) {
            return Route.DISCOVERY;
        }
        return switch (call.method()) {
            case "GET" -> Route.GET;
            case "PUT" -> Route.UPDATE;
            case "POST" -> path.endsWith("/complete") ? Route.COMPLETE : path.endsWith("/cancel") ? Route.CANCEL : Route.CREATE;
            default -> throw new AssertionError("unexpected " + call);
        };
    }

    @Override
    public synchronized HttpReply send(HttpCall call) throws IOException {
        calls.add(call);
        Route route = classify(call);
        Deque<HttpReply> replies = script.get(route);
        if (replies == null || replies.isEmpty()) {
            throw new AssertionError("no reply scripted for " + route + ": " + call);
        }
        return replies.size() > 1 ? replies.removeFirst() : replies.peekFirst();
    }
}
