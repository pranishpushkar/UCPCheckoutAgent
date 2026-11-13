package dev.ucplab.faultproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.faultproxy.Action.CorruptResponse;
import dev.ucplab.faultproxy.Action.Delay;
import dev.ucplab.faultproxy.Action.DuplicateRequest;
import dev.ucplab.faultproxy.Action.FailBeforeForward;
import dev.ucplab.faultproxy.Action.ForwardThenDelayResponse;
import dev.ucplab.faultproxy.Action.ForwardThenDropResponse;
import dev.ucplab.faultproxy.Action.ForwardThenRewriteResponse;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Each §12 action against a counting echo upstream. The upstream's count is the ground truth for whether the merchant
 * received a request, and how often; the log must say the same thing.
 */
class ActionsTest extends ProxyTestBase {

    private static final String COMPLETE = "/ucp/v1/checkout-sessions/cs_1/complete";
    private static final String COMPLETE_PATTERN = "/ucp/v1/checkout-sessions/*/complete";
    private static final String BODY = "{\"payment\":{\"credential\":{\"token\":\"tok_visa\"}}}";
    private static final Map<String, String> HEADERS = Map.of("Content-Type", "application/json",
            "Idempotency-Key", "ik_complete_r0", "Request-Id", "rq_1");

    private void rule(Action action) {
        proxy.install(new RuleSet(42, List.of(new Rule("r1", Match.of("POST", COMPLETE_PATTERN), action))));
    }

    private HttpResponse<String> complete() throws IOException, InterruptedException {
        return send("POST", COMPLETE, BODY, HEADERS);
    }

    private CompletableFuture<Object> completeAsync(Duration timeout) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", COMPLETE, BODY, HEADERS, timeout);
            } catch (Exception e) {
                return e;
            }
        });
    }

    private LogEntry only() {
        List<LogEntry> log = proxy.log(0);
        assertThat(log).hasSize(1);
        return log.getFirst();
    }

    private LogEntry awaitEntry(Predicate<LogEntry> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            List<LogEntry> log = proxy.log(0);
            if (!log.isEmpty() && condition.test(log.getFirst())) {
                return log.getFirst();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("log never reached the condition: " + proxy.log(0));
    }

    private static void assertLogged(LogEntry e, String action, int forwards, OptionalInt merchantStatus) {
        assertThat(e.action()).contains(action);
        assertThat(e.ruleId()).contains("r1");
        assertThat(e.forwards()).isEqualTo(forwards);
        assertThat(e.forwarded()).isEqualTo(forwards > 0);
        assertThat(e.merchantStatus()).isEqualTo(merchantStatus);
        assertThat(e.idempotencyKey()).contains("ik_complete_r0");
        assertThat(e.requestId()).contains("rq_1");
        assertThat(e.bodyHmac()).contains(hmac(BODY));
    }

    // Covers: §12 delay (the request waits, then is forwarded once; while held the log already shows it, not forwarded)
    @Test
    void delay_holdsThenForwards() throws Exception {
        rule(new Delay(400));
        long start = System.nanoTime();

        CompletableFuture<Object> reply = completeAsync(Duration.ofSeconds(10));
        LogEntry held = awaitEntry(e -> true);
        assertThat(held.forwarded()).isFalse();
        assertThat(upstream.arrivals()).isEmpty();

        assertThat(reply.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(HttpResponse.class,
                r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(400));
        assertThat(upstream.count("POST", COMPLETE)).isEqualTo(1);
        assertLogged(only(), "delay", 1, OptionalInt.of(200));
    }

    // Covers: §12 fail_before_forward (a status, with Retry-After, and the merchant is never contacted)
    @Test
    void failBeforeForward_status_answersWithoutContactingTheMerchant() throws Exception {
        rule(new FailBeforeForward(OptionalInt.of(503), false, OptionalInt.of(3)));

        HttpResponse<String> r = complete();

        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.headers().firstValue("Retry-After")).contains("3");
        assertThat(Json.readObject(r.body())).containsEntry("error", "fault_injected");
        assertThat(upstream.arrivals()).isEmpty();
        assertLogged(only(), "fail_before_forward", 0, OptionalInt.empty());
    }

    // Covers: §12 fail_before_forward (no Retry-After unless asked for)
    @Test
    void failBeforeForward_statusWithoutRetryAfter() throws Exception {
        rule(FailBeforeForward.status(500));

        HttpResponse<String> r = complete();

        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.headers().firstValue("Retry-After")).isEmpty();
        assertThat(upstream.arrivals()).isEmpty();
    }

    // Covers: §12 fail_before_forward, 8.1 (close: the client sees the connection end with no reply; nothing was sent)
    @Test
    void failBeforeForward_close_endsTheConnectionWithoutContactingTheMerchant() throws Exception {
        rule(FailBeforeForward.closeConnection());

        assertThatThrownBy(this::complete).isInstanceOf(IOException.class);

        assertThat(upstream.arrivals()).isEmpty();
        assertLogged(only(), "fail_before_forward", 0, OptionalInt.empty());
    }

    // Covers: §12 forward_then_drop_response, 8.1, I1 (the merchant receives the request exactly once and answers 200;
    // the client gets no reply, which is what makes the agent's outcome Uncertain and not NotSent)
    @Test
    void forwardThenDropResponse_merchantGetsItOnce_clientGetsNothing() throws Exception {
        rule(new ForwardThenDropResponse());

        assertThatThrownBy(this::complete).isInstanceOf(IOException.class)
                .isNotInstanceOf(HttpTimeoutException.class);

        Thread.sleep(200);
        assertThat(upstream.count("POST", COMPLETE)).isEqualTo(1);
        LogEntry e = only();
        assertLogged(e, "forward_then_drop_response", 1, OptionalInt.of(200));
        assertThat(e.upstreamError()).isFalse();
    }

    // Covers: §12 forward_then_delay_response, 8.1, I1 (the merchant has the request exactly once before the client's
    // timeout fires; the log shows merchantStatus while the reply is still held)
    @Test
    void forwardThenDelayResponse_merchantGetsItOnce_clientTimesOut() throws Exception {
        rule(new ForwardThenDelayResponse(1500));

        CompletableFuture<Object> reply = completeAsync(Duration.ofMillis(500));
        LogEntry held = awaitEntry(e -> e.merchantStatus().isPresent());
        assertThat(held.merchantStatus()).hasValue(200);
        assertThat(upstream.count("POST", COMPLETE)).isEqualTo(1);

        assertThat(reply.get(10, TimeUnit.SECONDS)).isInstanceOf(HttpTimeoutException.class);
        assertThat(upstream.count("POST", COMPLETE)).isEqualTo(1);
        Thread.sleep(1300);
        assertThat(upstream.count("POST", COMPLETE)).isEqualTo(1);
        assertLogged(only(), "forward_then_delay_response", 1, OptionalInt.of(200));
    }

    // Covers: §12 forward_then_delay_response (a patient client gets the merchant's reply after the hold)
    @Test
    void forwardThenDelayResponse_patientClientGetsTheReply() throws Exception {
        rule(new ForwardThenDelayResponse(300));
        long start = System.nanoTime();

        HttpResponse<String> r = complete();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"n\":1");
        assertThat(upstream.count("POST", COMPLETE)).isEqualTo(1);
        assertThat(only().clientGone()).isFalse();
    }

    // Covers: §12 duplicate_request parallel (both copies are in flight together, with identical headers and body;
    // forwards is 2)
    @Test
    void duplicateRequest_parallel_bothCopiesInFlightTogether() throws Exception {
        rule(new DuplicateRequest(DuplicateRequest.Mode.PARALLEL));
        CountDownLatch both = new CountDownLatch(2);
        upstream.respondWith((ex, a) -> {
            both.countDown();
            try {
                boolean together = both.await(5, TimeUnit.SECONDS);
                EchoUpstream.send(ex, together ? 200 : 500, "{\"n\":" + a.n() + "}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        HttpResponse<String> r = complete();

        assertThat(r.statusCode()).isEqualTo(200);
        List<EchoUpstream.Arrival> arrivals = upstream.arrivals();
        assertThat(arrivals).hasSize(2);
        for (String h : List.of("Idempotency-Key", "Request-Id", "Content-Type")) {
            assertThat(arrivals.get(0).header(h)).isEqualTo(arrivals.get(1).header(h)).isNotEmpty();
        }
        assertThat(arrivals.get(0).body()).isEqualTo(arrivals.get(1).body()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8));
        assertLogged(only(), "duplicate_request", 2, OptionalInt.of(200));
    }

    // Covers: §12 duplicate_request sequential (the second copy goes after the first is answered; the client gets the
    // first copy's reply)
    @Test
    void duplicateRequest_sequential_secondCopyAfterTheFirstReply() throws Exception {
        rule(new DuplicateRequest(DuplicateRequest.Mode.SEQUENTIAL));
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        upstream.respondWith((ex, a) -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(100);
                EchoUpstream.send(ex, a.n() == 1 ? 201 : 200, "{\"n\":" + a.n() + "}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        });

        HttpResponse<String> r = complete();

        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(r.body()).isEqualTo("{\"n\":1}");
        assertThat(upstream.count("POST", COMPLETE)).isEqualTo(2);
        assertThat(maxInFlight).hasValue(1);
        assertLogged(only(), "duplicate_request", 2, OptionalInt.of(201));
    }

    // Covers: §12 corrupt_response truncate (the first half of the body with a matching Content-Length: valid HTTP,
    // invalid JSON)
    @Test
    void corruptResponse_truncate_halvesTheBody() throws Exception {
        String merchantBody = "{\"id\":\"cs_1\",\"status\":\"completed\",\"order\":{\"id\":\"ord_1\"}}";
        upstream.respondWith((ex, a) -> EchoUpstream.send(ex, 200, merchantBody));
        rule(new CorruptResponse(CorruptResponse.Kind.TRUNCATE));

        HttpResponse<String> r = complete();

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(merchantBody.substring(0, merchantBody.length() / 2));
        assertThat(r.headers().firstValueAsLong("Content-Length")).hasValue(merchantBody.length() / 2);
        assertThatThrownBy(() -> Json.read(r.body())).isInstanceOf(IllegalArgumentException.class);
        assertThat(upstream.count("POST", COMPLETE)).isEqualTo(1);
        assertLogged(only(), "corrupt_response", 1, OptionalInt.of(200));
    }

    // Covers: §12 corrupt_response garble (every '"' becomes '\'', same length, never valid JSON)
    @Test
    void corruptResponse_garble_replacesQuotes() throws Exception {
        String merchantBody = "{\"id\":\"cs_1\",\"status\":\"completed\"}";
        upstream.respondWith((ex, a) -> EchoUpstream.send(ex, 200, merchantBody));
        rule(new CorruptResponse(CorruptResponse.Kind.GARBLE));

        HttpResponse<String> r = complete();

        assertThat(r.body()).isEqualTo(merchantBody.replace('"', '\'')).hasSize(merchantBody.length());
        assertThatThrownBy(() -> Json.read(r.body())).isInstanceOf(IllegalArgumentException.class);
        assertThat(upstream.count("POST", COMPLETE)).isEqualTo(1);
        assertLogged(only(), "corrupt_response", 1, OptionalInt.of(200));
    }

    // Covers: §12 forward_then_rewrite_response, S10 (forwarded once; every occurrence of the literal is replaced, the
    // body stays JSON and Content-Length matches the new length; the log says a replacement happened, I8: the log holds
    // neither the body nor the strings)
    @Test
    void forwardThenRewriteResponse_replacesEveryOccurrenceAndFixesContentLength() throws Exception {
        String merchantBody = "{\"title\":\"Blue Mug\",\"description\":{\"plain\":\"Blue Mug\"}}";
        upstream.respondWith((ex, a) -> EchoUpstream.send(ex, 200, merchantBody));
        rule(new ForwardThenRewriteResponse("Blue Mug", "SECRET-REPLACEMENT longer than the find"));

        HttpResponse<String> r = complete();

        String expected = merchantBody.replace("Blue Mug", "SECRET-REPLACEMENT longer than the find");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(expected);
        assertThat(r.headers().firstValueAsLong("Content-Length"))
                .hasValue(expected.getBytes(StandardCharsets.UTF_8).length);
        assertThat(Json.readObject(r.body())).containsEntry("title", "SECRET-REPLACEMENT longer than the find");
        assertThat(upstream.count("POST", COMPLETE)).isEqualTo(1);
        LogEntry e = only();
        assertLogged(e, "forward_then_rewrite_response", 1, OptionalInt.of(200));
        assertThat(e.rewrite()).contains("replaced");
        String logJson = Json.write(e.toJson());
        assertThat(logJson).contains("\"rewrite\":\"replaced\"").doesNotContain("SECRET").doesNotContain("Blue Mug");
    }

    // Covers: §12 forward_then_rewrite_response (a JSON body without the literal is returned unchanged, logged no_match)
    @Test
    void forwardThenRewriteResponse_noMatch_isUnchangedAndLogged() throws Exception {
        String merchantBody = "{\"title\":\"Red Shirt\"}";
        upstream.respondWith((ex, a) -> EchoUpstream.send(ex, 200, merchantBody));
        rule(new ForwardThenRewriteResponse("Blue Mug", "hostile"));

        HttpResponse<String> r = complete();

        assertThat(r.body()).isEqualTo(merchantBody);
        assertThat(r.headers().firstValueAsLong("Content-Length")).hasValue(merchantBody.length());
        assertThat(upstream.count("POST", COMPLETE)).isEqualTo(1);
        assertThat(only().rewrite()).contains("no_match");
    }

    // Covers: §12 forward_then_rewrite_response (a body that is not JSON is returned unchanged, even when it holds the
    // literal, and logged not_json)
    @Test
    void forwardThenRewriteResponse_notJson_isUnchangedAndLogged() throws Exception {
        String merchantBody = "Blue Mug, as plain text";
        upstream.respondWith((ex, a) -> {
            byte[] bytes = merchantBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, bytes.length);
            try (var os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        rule(new ForwardThenRewriteResponse("Blue Mug", "hostile"));

        HttpResponse<String> r = complete();

        assertThat(r.body()).isEqualTo(merchantBody);
        assertThat(upstream.count("POST", COMPLETE)).isEqualTo(1);
        assertThat(only().rewrite()).contains("not_json");
    }

    // Covers: §12 forward_then_rewrite_response (find and replace are both non-empty)
    @Test
    void forwardThenRewriteResponse_emptyStrings_areRefused() {
        assertThatThrownBy(() -> new ForwardThenRewriteResponse("", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ForwardThenRewriteResponse("x", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ForwardThenRewriteResponse(null, "x")).isInstanceOf(NullPointerException.class);
    }

    // Covers: §12 Admin API (an entry with no rewrite rule has no rewrite outcome)
    @Test
    void forward_withoutRewriteRule_hasNoRewriteOutcome() throws Exception {
        complete();

        assertThat(only().rewrite()).isEmpty();
        assertThat(only().toJson()).containsEntry("rewrite", Json.NULL);
    }

    // Covers: §12 Matching (nth 1: only the first matching request is faulted; the next is forwarded and logged
    // with no action)
    @Test
    void rule_nth_faultsOnlyThatRequest() throws Exception {
        proxy.install(new RuleSet(42, List.of(new Rule("r1", Match.of("POST", COMPLETE_PATTERN).withNth(1),
                FailBeforeForward.status(503)))));

        assertThat(complete().statusCode()).isEqualTo(503);
        assertThat(complete().statusCode()).isEqualTo(200);

        List<LogEntry> log = proxy.log(0);
        assertThat(log.get(0).ruleId()).contains("r1");
        assertThat(log.get(1).ruleId()).isEmpty();
        assertThat(log.get(1).action()).isEmpty();
        assertThat(upstream.count("POST", COMPLETE)).isEqualTo(1);
    }

    // Covers: §12 Matching (a request already in flight keeps the rule set it started with)
    @Test
    void rule_inFlightRequestKeepsItsRuleSet() throws Exception {
        rule(new Delay(400));

        CompletableFuture<Object> reply = completeAsync(Duration.ofSeconds(10));
        awaitEntry(e -> true);
        proxy.clearRules();

        assertThat(reply.get(10, TimeUnit.SECONDS)).isInstanceOf(HttpResponse.class);
        assertThat(only().action()).contains("delay");
        assertThat(complete().statusCode()).isEqualTo(200);
        assertThat(proxy.log(0).get(1).action()).isEmpty();
    }

    // Covers: §12 Safety, risk 5 (against an upstream that closes every connection, each POST arrives exactly once:
    // the proxy's client never re-sends on its own)
    @Test
    void forward_upstreamClosingConnections_eachPostArrivesOnce() throws Exception {
        upstream.respondWith((ex, a) -> {
            ex.getResponseHeaders().set("Connection", "close");
            EchoUpstream.echo(ex, a);
        });

        for (int i = 0; i < 30; i++) {
            assertThat(post("/ucp/v1/checkout-sessions", "{\"i\":" + i + "}").statusCode()).isEqualTo(200);
        }

        assertThat(upstream.count("POST", "/ucp/v1/checkout-sessions")).isEqualTo(30);
        assertThat(proxy.log(0)).hasSize(30).allSatisfy(e -> assertThat(e.forwards()).isEqualTo(1));
    }
}
