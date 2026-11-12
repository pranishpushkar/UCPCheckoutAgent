package dev.ucplab.faultproxy;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The admin API under {@code /_proxy/} (§12): {@code POST /_proxy/rules} replaces the rule set, {@code DELETE
 * /_proxy/rules} clears it, and {@code GET /_proxy/log[?after=<seq>]} lists the log. These are the only routes: there is
 * none that reads or sets the {@code bodyHmac} key, and a rule set with an extra field (a key included) is refused.
 */
final class AdminApi {

    /** Every admin route. Anything else under {@code /_proxy} is 404. */
    static final Set<String> ROUTES = Set.of("/_proxy/rules", "/_proxy/log");

    private static final int MAX_RULES_BYTES = 1024 * 1024;

    private final FaultProxy proxy;

    AdminApi(FaultProxy proxy) {
        this.proxy = proxy;
    }

    void handle(HttpExchange ex, String rawPath, String rawQuery) throws IOException {
        String method = ex.getRequestMethod();
        switch (rawPath) {
            case "/_proxy/rules" -> {
                if (rawQuery != null) {
                    FaultProxy.respond(ex, 400, "{\"error\":\"unexpected_query\"}");
                } else if (method.equals("POST")) {
                    replaceRules(ex);
                } else if (method.equals("DELETE")) {
                    proxy.clearRules();
                    FaultProxy.respond(ex, 204, "");
                } else {
                    notAllowed(ex, "POST, DELETE");
                }
            }
            case "/_proxy/log" -> {
                if (method.equals("GET")) {
                    readLog(ex, rawQuery);
                } else {
                    notAllowed(ex, "GET");
                }
            }
            default -> FaultProxy.respond(ex, 404, "{\"error\":\"not_found\"}");
        }
    }

    private void replaceRules(HttpExchange ex) throws IOException {
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readNBytes(MAX_RULES_BYTES + 1);
        }
        if (body.length > MAX_RULES_BYTES) {
            FaultProxy.respond(ex, 413, "{\"error\":\"rules_too_large\"}");
            return;
        }
        RuleSet rules;
        try {
            rules = RuleSet.parse(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            FaultProxy.respond(ex, 400, Json.write(Map.of("error", "invalid_rules", "detail", e.getMessage())));
            return;
        }
        proxy.install(rules);
        FaultProxy.respond(ex, 204, "");
    }

    private void readLog(HttpExchange ex, String rawQuery) throws IOException {
        long after = 0;
        if (rawQuery != null) {
            if (!rawQuery.matches("after=(0|[1-9][0-9]{0,17})")) {
                FaultProxy.respond(ex, 400, "{\"error\":\"bad_query\",\"detail\":\"only after=<non-negative seq>\"}");
                return;
            }
            after = Long.parseLong(rawQuery.substring("after=".length()));
        }
        List<Object> entries = proxy.log(after).stream().<Object>map(LogEntry::toJson).toList();
        FaultProxy.respond(ex, 200, Json.write(entries));
    }

    private static void notAllowed(HttpExchange ex, String allow) throws IOException {
        ex.getResponseHeaders().set("Allow", allow);
        FaultProxy.respond(ex, 405, "{\"error\":\"method_not_allowed\"}");
    }
}
