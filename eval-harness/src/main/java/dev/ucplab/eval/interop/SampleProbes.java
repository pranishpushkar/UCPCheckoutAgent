package dev.ucplab.eval.interop;

import dev.ucplab.core.json.LenientJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Small direct probes of what the sample answers (H1 C8b), each recording a status and a short body. They answer the
 * VERIFYs that no agent row reaches: the same idempotency key with a different body, a call without {@code Request-Id} or
 * without {@code UCP-Agent}.
 */
final class SampleProbes {

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final SampleMerchant sample;
    private final String agent;

    SampleProbes(SampleMerchant sample, String ucpAgent) {
        this.sample = sample;
        this.agent = ucpAgent + "; version=\"2026-04-08\"";
    }

    private record Reply(int status, String body) {
        Map<String, Object> json() {
            try {
                return LenientJson.readObject(body);
            } catch (RuntimeException e) {
                return Map.of();
            }
        }

        String brief() {
            return body.length() > 200 ? body.substring(0, 200) + "..." : body;
        }
    }

    private Reply send(String method, String path, String body, String key, boolean requestId, boolean agentHeader) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(sample.baseUrl() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (agentHeader) {
            b.header("UCP-Agent", agent);
        }
        if (requestId) {
            b.header("Request-Id", UUID.randomUUID().toString());
        }
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body());
    }

    private static String pay(String instrumentId) {
        return "{\"payment\":{\"instruments\":[{\"credential\":{\"token\":\"" + SampleWiring.SUCCESS_TOKEN
                + "\",\"type\":\"token\"},\"handler_id\":\"mock_payment_handler\",\"id\":\"" + instrumentId + "\",\"type\":\"card\"}]}}";
    }

    /** A checkout driven to ready_for_complete by the three selection updates; returns its id. */
    private String readyCheckout() throws Exception {
        String create = "{\"buyer\":{\"email\":\"john.doe@example.com\"},\"line_items\":[{\"item\":{\"id\":\"" + InteropRows.ITEM
                + "\"},\"quantity\":1}]}";
        Reply c = send("POST", "/checkout-sessions", create, UUID.randomUUID().toString(), true, true);
        String id = (String) c.json().get("id");
        if (id == null) {
            throw new IllegalStateException("probe create answered " + c.status() + " " + c.brief());
        }
        @SuppressWarnings("unchecked")
        String line = (String) ((Map<String, Object>) ((List<?>) c.json().get("line_items")).get(0)).get("id");
        String base = "\"buyer\":{\"email\":\"john.doe@example.com\"},\"line_items\":[{\"id\":\"" + line + "\",\"item\":{\"id\":\""
                + InteropRows.ITEM + "\"},\"quantity\":1}]";
        String method = "{\"id\":\"method_1\",\"line_item_ids\":[\"" + line + "\"],\"type\":\"shipping\"";
        send("PUT", "/checkout-sessions/" + id, "{" + base + ",\"fulfillment\":{\"methods\":[" + method + "}]}}", UUID.randomUUID().toString(), true, true);
        Reply d = send("PUT", "/checkout-sessions/" + id, "{" + base + ",\"fulfillment\":{\"methods\":[" + method
                + ",\"selected_destination_id\":\"addr_1\"}]}}", UUID.randomUUID().toString(), true, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) ((List<?>) ((Map<?, ?>) d.json().get("fulfillment")).get("methods")).get(0);
        @SuppressWarnings("unchecked")
        String group = (String) ((Map<String, Object>) ((List<?>) m.get("groups")).get(0)).get("id");
        send("PUT", "/checkout-sessions/" + id, "{" + base + ",\"fulfillment\":{\"methods\":[{\"groups\":[{\"id\":\"" + group
                + "\",\"line_item_ids\":[\"" + line + "\"],\"selected_option_id\":\"std-ship\"}],\"id\":\"method_1\",\"line_item_ids\":[\""
                + line + "\"],\"selected_destination_id\":\"addr_1\",\"type\":\"shipping\"}]}}", UUID.randomUUID().toString(), true, true);
        return id;
    }

    /** All probes; each line is a finding, with the evidence it rests on. */
    List<String> run() throws Exception {
        List<String> out = new ArrayList<>();
        String id = readyCheckout();
        String key = UUID.randomUUID().toString();
        int before = sample.orders().size();
        Reply first = send("POST", "/checkout-sessions/" + id + "/complete", pay("instr_a"), key, true, true);
        Reply same = send("POST", "/checkout-sessions/" + id + "/complete", pay("instr_a"), key, true, true);
        Reply different = send("POST", "/checkout-sessions/" + id + "/complete", pay("instr_b_different_body"), key, true, true);
        out.add("S17 first complete: " + first.status() + " " + first.json().get("status") + "; same key and same body: "
                + same.status() + " " + same.json().get("status") + "; same key and a DIFFERENT body: " + different.status() + " "
                + different.brief() + "; orders created by the three: " + (sample.orders().size() - before));
        String create = "{\"buyer\":{\"email\":\"john.doe@example.com\"},\"line_items\":[{\"item\":{\"id\":\"" + InteropRows.ITEM
                + "\"},\"quantity\":1}]}";
        Reply noRequestId = send("POST", "/checkout-sessions", create, UUID.randomUUID().toString(), false, true);
        Reply noAgent = send("POST", "/checkout-sessions", create, UUID.randomUUID().toString(), true, false);
        Reply noKey = send("POST", "/checkout-sessions", create, null, true, true);
        out.add("create without Request-Id: " + noRequestId.status() + " " + noRequestId.brief());
        out.add("create without UCP-Agent: " + noAgent.status() + " " + noAgent.brief());
        out.add("create without Idempotency-Key: " + noKey.status() + " " + noKey.brief());
        return out;
    }
}
