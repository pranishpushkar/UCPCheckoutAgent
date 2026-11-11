package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.FrozenBody;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class FailureClassifierTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final FailureClassifier CLASSIFIER = new FailureClassifier(Clock.fixed(NOW, ZoneOffset.UTC));
    private static final List<AttemptOp> MUTATING = List.of(AttemptOp.CREATE, AttemptOp.UPDATE, AttemptOp.COMPLETE,
            AttemptOp.CANCEL);
    private static final List<AttemptOp> ALL_OPS = List.of(AttemptOp.CREATE, AttemptOp.UPDATE, AttemptOp.COMPLETE,
            AttemptOp.CANCEL, AttemptOp.GET);

    private static byte[] fixture(String name) {
        try (InputStream in = FailureClassifierTest.class.getResourceAsStream("/merchant/" + name + ".json")) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpReply reply(int status, byte[] body, String... headers) {
        java.util.Map<String, List<String>> h = new java.util.LinkedHashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            h.put(headers[i].toLowerCase(), List.of(headers[i + 1]));
        }
        return new HttpReply(status, h, body);
    }

    private static HttpReply reply(int status, String body, String... headers) {
        return reply(status, body.getBytes(StandardCharsets.UTF_8), headers);
    }

    private static CallOutcome<CheckoutView> classify(AttemptOp op, HttpReply reply) {
        return CLASSIFIER.classify(op, reply);
    }

    private static boolean mutating(AttemptOp op) {
        return MUTATING.contains(op);
    }

    // ---------------------------------------------------------------- failures: NotSent (8.1 row 1, decision A)

    // Covers: 8.1 (HttpConnectTimeoutException: the connection was never made; checked before HttpTimeoutException),
    // decision A
    @Test
    void classifyFailure_isNotSent_forConnectTimeout_onEveryOp() {
        for (AttemptOp op : ALL_OPS) {
            assertThat(CLASSIFIER.classify(op, new HttpConnectTimeoutException("connect timed out")))
                    .as(op.name()).isInstanceOf(CallOutcome.NotSent.class);
        }
    }

    // Covers: 8.1 (connection refused and DNS failure are ConnectExceptions raised before anything is written),
    // decision A (JdkFailureModesTest pins that the JDK raises ConnectException only before a connection exists)
    @Test
    void classifyFailure_isNotSent_forConnectException_refusedOrUnresolved() {
        ConnectException unresolved = new ConnectException();
        unresolved.initCause(new UnresolvedAddressException());
        for (AttemptOp op : ALL_OPS) {
            assertThat(CLASSIFIER.classify(op, new ConnectException("Connection refused")))
                    .as(op.name()).isInstanceOf(CallOutcome.NotSent.class);
            assertThat(CLASSIFIER.classify(op, unresolved)).as(op.name()).isInstanceOf(CallOutcome.NotSent.class);
        }
    }

    // Covers: 8.1 (a TLS handshake failure happens before any request byte is written)
    @Test
    void classifyFailure_isNotSent_forTlsHandshakeFailure() {
        for (AttemptOp op : ALL_OPS) {
            assertThat(CLASSIFIER.classify(op, new SSLHandshakeException("Remote host terminated the handshake")))
                    .as(op.name()).isInstanceOf(CallOutcome.NotSent.class);
        }
    }

    // Covers: 8.1, 8.3 (a call rejected by the open circuit breaker never left: NotSent)
    @Test
    void circuitOpen_isNotSent() {
        assertThat(FailureClassifier.circuitOpen("127.0.0.1:8081")).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                n -> assertThat(n.cause()).contains("circuit").contains("127.0.0.1:8081"));
    }

    // ---------------------------------------------------------------- failures: Uncertain (8.1 row 2)

    // Covers: 8.1 (request written, then a timeout, reset, EOF or any other IOException: Uncertain), decision A. The
    // listed exceptions are what the JDK raises after the request may have been written (JdkFailureModesTest).
    @Test
    void classifyFailure_isUncertain_forEveryOtherFailure_onEveryOp() {
        IOException noBytes = new IOException("HTTP/1.1 header parser received no bytes", new EOFException("EOF"));
        IOException reset = new IOException("HTTP/1.1 header parser received no bytes",
                new SocketException("Connection reset"));
        // A plain IOException that merely has a ConnectException somewhere inside is not a connect failure.
        IOException wrapsConnect = new IOException("wrapper", new ConnectException("inner"));
        List<Throwable> failures = List.of(new HttpTimeoutException("request timed out"), noBytes, reset,
                new SocketTimeoutException("Read timed out"), new InterruptedIOException("interrupted"),
                new SSLException("Unexpected close_notify"), new EOFException(), new ClosedChannelException(),
                new IOException("response body larger than 1048576 bytes"), wrapsConnect,
                new IllegalStateException("anything unexpected"));
        for (AttemptOp op : ALL_OPS) {
            for (Throwable failure : failures) {
                assertThat(CLASSIFIER.classify(op, failure)).as(op + " " + failure).isInstanceOf(CallOutcome.Uncertain.class);
            }
        }
    }

    // Covers: 8.1, I8 (the cause names the exception and a cleaned message; control characters never pass through)
    @Test
    void classifyFailure_cause_isCleaned() {
        CallOutcome<CheckoutView> outcome = CLASSIFIER.classify(AttemptOp.COMPLETE,
                new IOException("bad\nline\u0007" + "x".repeat(500)));

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.Uncertain.class, u ->
                assertThat(u.cause()).startsWith("IOException").doesNotContain("\n").doesNotContain("\u0007")
                        .hasSizeLessThan(260));
    }

    // Covers: 8.1 (real exceptions from the real transport: a closed port is NotSent)
    @Test
    void classifyFailure_realClosedPort_isNotSent() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        IOException failure = failureOf(URI.create("http://127.0.0.1:" + closed + "/x"), false);

        assertThat(CLASSIFIER.classify(AttemptOp.CREATE, failure)).isInstanceOf(CallOutcome.NotSent.class);
    }

    // Covers: 8.1 (real exceptions from the real transport: a connection reset after the request is Uncertain)
    @Test
    void classifyFailure_realResetAfterRequest_isUncertain() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    Socket s = server.accept();
                    s.getInputStream().read(new byte[16]);
                    s.setSoLinger(true, 0);
                    s.close();
                } catch (IOException ignored) {
                    // closed
                }
            });
            IOException failure = failureOf(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/x"), true);
            acceptor.interrupt();

            assertThat(CLASSIFIER.classify(AttemptOp.COMPLETE, failure)).isInstanceOf(CallOutcome.Uncertain.class);
        }
    }

    private static IOException failureOf(URI uri, boolean withBody) {
        try {
            new JdkHttpTransport(UcpConfig.defaults("profile=\"https://a.example/.well-known/ucp\"")).send(new HttpCall(
                    "POST", uri, Map.of(), Optional.of(new FrozenBody("{}")), Duration.ofSeconds(3)));
            throw new AssertionError("expected an IOException");
        } catch (IOException e) {
            return e;
        }
    }

    // ---------------------------------------------------------------- replies: Ok and Rejected (8.1 rows 3, 4)

    // Covers: 8.1 (HTTP 200 or 201 with a valid body is Ok, business outcomes inside messages), Appendix B
    @Test
    void classifyReply_isOk_forValidBody_withItsMessages() {
        CallOutcome<CheckoutView> created = classify(AttemptOp.CREATE, reply(201, fixture("create_ready")));
        CallOutcome<CheckoutView> declined = classify(AttemptOp.COMPLETE, reply(200, fixture("complete_declined")));

        assertThat(created).isInstanceOfSatisfying(CallOutcome.Ok.class, ok -> {
            assertThat(ok.httpStatus()).isEqualTo(201);
            assertThat(((CheckoutView) ok.value()).status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
            assertThat(ok.messages()).isEmpty();
        });
        assertThat(declined).isInstanceOfSatisfying(CallOutcome.Ok.class, ok -> {
            assertThat(ok.httpStatus()).isEqualTo(200);
            assertThat(ok.messages()).singleElement().satisfies(m -> assertThat(((dev.ucplab.core.merchant.MerchantMessage) m).code())
                    .isEqualTo("payment_declined"));
        });
    }

    // Covers: 8.1 (200 and 201 are both accepted for any operation; status alone does not decide)
    @Test
    void classifyReply_acceptsBoth200And201_onEveryOp() {
        for (AttemptOp op : ALL_OPS) {
            for (int status : new int[] {200, 201}) {
                assertThat(classify(op, reply(status, fixture("get_ready")))).as(op + " " + status)
                        .isInstanceOf(CallOutcome.Ok.class);
            }
        }
    }

    // Covers: 8.1, decision N (200 with ucp.status error and no id is Rejected, valid for create only)
    @Test
    void classifyReply_isRejected_forCreateWithEveryItemUnavailable() {
        assertThat(classify(AttemptOp.CREATE, reply(200, fixture("create_all_unavailable"))))
                .isInstanceOfSatisfying(CallOutcome.Rejected.class, r -> {
                    assertThat(r.httpStatus()).isEqualTo(200);
                    assertThat(r.messages()).hasSize(1);
                });
    }

    // Covers: 8.1, decision N, B (the same envelope on any other operation is not a checkout: Uncertain for a mutating
    // call, ProtocolError for get)
    @Test
    void classifyReply_rejectedEnvelope_onOtherOps_isInvalid() {
        for (AttemptOp op : List.of(AttemptOp.UPDATE, AttemptOp.COMPLETE, AttemptOp.CANCEL, AttemptOp.GET)) {
            CallOutcome<CheckoutView> outcome = classify(op, reply(200, fixture("create_all_unavailable")));

            if (mutating(op)) {
                assertThat(outcome).as(op.name()).isInstanceOf(CallOutcome.Uncertain.class);
            } else {
                assertThat(outcome).as(op.name()).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                        e -> assertThat(e.code()).isEqualTo("invalid_response"));
            }
        }
    }

    // ---------------------------------------------------------------- replies: ProtocolError (8.1 rows 5, 6)

    // Covers: 8.1 (400, 401, 403, 404, 409 and 422 are ProtocolError and never retryable: no retryAfter), on every op
    // including complete
    @Test
    void classifyReply_isProtocolError_forClientErrors_onEveryOp() {
        for (int status : new int[] {400, 401, 403, 404, 409, 422}) {
            for (AttemptOp op : ALL_OPS) {
                assertThat(classify(op, reply(status, fixture("error_not_found")))).as(op + " " + status)
                        .isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
                            assertThat(e.httpStatus()).isEqualTo(status);
                            assertThat(e.code()).isEqualTo("not_found");
                            assertThat(e.content()).isNotBlank();
                            assertThat(e.retryAfter()).isEmpty();
                        });
            }
        }
    }

    // Covers: 8.1, A.1 (the merchant's real 409 for a reused key and 400 for a missing header)
    @Test
    void classifyReply_readsCodeOfTheMerchantsRealErrors() {
        assertThat(classify(AttemptOp.CREATE, reply(409, fixture("error_idempotency_conflict"))))
                .isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                        e -> assertThat(e.code()).isEqualTo("idempotency_key_reuse"));
        assertThat(classify(AttemptOp.CREATE, reply(400, fixture("error_missing_header"))))
                .isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                        e -> assertThat(e.code()).isEqualTo("missing_header"));
    }

    // Covers: 8.1 (an error body that is not {code, content} still gives a ProtocolError, with code unknown)
    @Test
    void classifyReply_protocolErrorWithUnreadableBody_hasCodeUnknown() {
        assertThat(classify(AttemptOp.UPDATE, reply(403, "<html>forbidden</html>")))
                .isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
                    assertThat(e.code()).isEqualTo("unknown");
                    assertThat(e.content()).isEmpty();
                });
    }

    // Covers: 8.1, 8.2 (HTTP 429 and 503 are ProtocolError with retryAfter, for every operation except complete)
    @Test
    void classifyReply_isProtocolErrorWithRetryAfter_for429And503_exceptComplete() {
        for (int status : new int[] {429, 503}) {
            for (AttemptOp op : List.of(AttemptOp.CREATE, AttemptOp.UPDATE, AttemptOp.CANCEL, AttemptOp.GET)) {
                assertThat(classify(op, reply(status, fixture("error_not_found"), "Retry-After", "2")))
                        .as(op + " " + status).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
                            assertThat(e.httpStatus()).isEqualTo(status);
                            assertThat(e.retryAfter()).contains(Duration.ofSeconds(2));
                        });
            }
        }
    }

    // Covers: 8.2, decision D (complete with 429 or 503: the gateway converts it to Uncertain, so the orchestrator
    // goes to RECONCILING; a 429 or 503 does not prove the request never reached the merchant)
    @Test
    void classifyReply_isUncertain_for429And503_onComplete() {
        for (int status : new int[] {429, 503}) {
            assertThat(classify(AttemptOp.COMPLETE, reply(status, fixture("error_not_found"), "Retry-After", "2")))
                    .isInstanceOfSatisfying(CallOutcome.Uncertain.class, u -> assertThat(u.cause()).contains("" + status));
        }
    }

    // Covers: 8.2 (Retry-After is seconds or an HTTP-date parsed against the injected Clock; absent, unreadable or
    // negative gives no retryAfter; a past date is zero; the value is clamped to one day)
    @Test
    void classifyReply_parsesRetryAfter_secondsOrHttpDate() {
        record Case(String header, Optional<Duration> expected) {}
        List<Case> cases = List.of(
                new Case("2", Optional.of(Duration.ofSeconds(2))),
                new Case("0", Optional.of(Duration.ZERO)),
                new Case("120", Optional.of(Duration.ofSeconds(120))),
                new Case("Thu, 12 Nov 2026 10:00:03 GMT", Optional.of(Duration.ofSeconds(3))),
                new Case("Thu, 12 Nov 2026 09:59:00 GMT", Optional.of(Duration.ZERO)),
                new Case("999999999999", Optional.of(Duration.ofDays(1))),
                new Case("-5", Optional.empty()),
                new Case("soon", Optional.empty()),
                new Case("", Optional.empty()),
                new Case("1.5", Optional.empty()));
        for (Case c : cases) {
            assertThat(classify(AttemptOp.CREATE, reply(429, fixture("error_not_found"), "Retry-After", c.header())))
                    .as("Retry-After: " + c.header()).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                            e -> assertThat(e.retryAfter()).isEqualTo(c.expected()));
        }
        assertThat(classify(AttemptOp.CREATE, reply(503, fixture("error_not_found"))))
                .isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> assertThat(e.retryAfter()).isEmpty());
    }

    // ---------------------------------------------------------------- replies: 5xx and unlisted statuses

    // Covers: 8.1 (500, 502, 504 and any other 5xx except 501 and 503: Uncertain for a mutating call; for get also
    // Uncertain, which the retry layer retries until it runs out)
    @Test
    void classifyReply_isUncertain_forOther5xx() {
        for (int status : new int[] {500, 502, 504, 505, 599}) {
            for (AttemptOp op : ALL_OPS) {
                assertThat(classify(op, reply(status, "boom"))).as(op + " " + status)
                        .isInstanceOfSatisfying(CallOutcome.Uncertain.class,
                                u -> assertThat(u.cause()).contains("" + status));
            }
        }
    }

    // Covers: 8.1, decision C (a status not listed: Uncertain for a mutating call, ProtocolError for get, and
    // redirects are never followed, so a 3xx is just such a status). 501 is such a status too.
    @Test
    void classifyReply_unlistedStatus_isUncertainForMutating_andProtocolErrorForGet() {
        for (int status : new int[] {100, 202, 204, 206, 301, 302, 303, 307, 308, 402, 405, 410, 413, 415, 423, 501}) {
            for (AttemptOp op : ALL_OPS) {
                CallOutcome<CheckoutView> outcome = classify(op, reply(status, "x", "Location", "http://elsewhere/"));

                if (mutating(op)) {
                    assertThat(outcome).as(op + " " + status).isInstanceOf(CallOutcome.Uncertain.class);
                } else {
                    assertThat(outcome).as(op + " " + status).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
                        assertThat(e.httpStatus()).isEqualTo(status);
                        assertThat(e.code()).isEqualTo("unexpected_status");
                        assertThat(e.retryAfter()).isEmpty();
                    });
                }
            }
        }
    }

    // ---------------------------------------------------------------- replies: invalid bodies (8.1 last row, decision B)

    private static final String LINES = "[{\"id\":\"li_1\",\"item\":{\"id\":\"item_123\",\"price\":2500},\"quantity\":2}]";
    private static final String TOTALS = "[{\"type\":\"total\",\"amount\":5000}]";

    private static String checkout(String status) {
        return "{\"id\":\"chk_1\",\"status\":" + status + ",\"currency\":\"USD\",\"line_items\":" + LINES + ",\"totals\":"
                + TOTALS + "}";
    }

    // Covers: 8.1, decision B (an unknown status string, requires_escalation, a missing required field, or an unparseable
    // body, on a 200 or 201: Uncertain for create, update, complete and cancel; ProtocolError for get)
    @Test
    void classifyReply_invalidBody_isUncertainForMutating_andProtocolErrorForGet() {
        List<String> bodies = List.of(
                checkout("\"weird\""),
                checkout("\"requires_escalation\""),
                "{\"status\":\"incomplete\",\"currency\":\"USD\",\"line_items\":" + LINES + ",\"totals\":" + TOTALS + "}",
                "{\"id\":\"chk_1\",\"status\":\"incomplete\",\"currency\":\"USD\",\"line_items\":" + LINES + "}",
                "{\"id\":\"chk_1\",\"status\":\"incomplete\"",
                "<html>502 Bad Gateway</html>",
                "");
        for (int status : new int[] {200, 201}) {
            for (String body : bodies) {
                for (AttemptOp op : ALL_OPS) {
                    CallOutcome<CheckoutView> outcome = classify(op, reply(status, body));

                    if (mutating(op)) {
                        assertThat(outcome).as(op + " " + status + " " + body)
                                .isInstanceOfSatisfying(CallOutcome.Uncertain.class,
                                        u -> assertThat(u.cause()).startsWith("invalid response"));
                    } else {
                        assertThat(outcome).as(op + " " + status + " " + body)
                                .isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
                                    assertThat(e.httpStatus()).isEqualTo(status);
                                    assertThat(e.code()).isEqualTo("invalid_response");
                                    assertThat(e.retryAfter()).isEmpty();
                                });
                    }
                }
            }
        }
    }

    // Covers: 8.1, 7.2 (requires_escalation is a status the contract does not model; on complete it must be Uncertain,
    // so the intent reconciles instead of being settled by a body we cannot read), spec-notes.md
    @Test
    void classifyReply_requiresEscalationOnComplete_isUncertain() {
        assertThat(classify(AttemptOp.COMPLETE, reply(200, checkout("\"requires_escalation\""))))
                .isInstanceOf(CallOutcome.Uncertain.class);
    }

    // Covers: I8 (an Uncertain or ProtocolError built from an invalid body never repeats merchant text)
    @Test
    void classifyReply_invalidBody_neverEchoesMerchantText() {
        HttpReply bad = reply(200, checkout("\"secret_canary_status\""));

        assertThat(classify(AttemptOp.COMPLETE, bad)).isInstanceOfSatisfying(CallOutcome.Uncertain.class,
                u -> assertThat(u.cause()).doesNotContain("canary"));
        assertThat(classify(AttemptOp.GET, bad)).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.content()).doesNotContain("canary"));
    }
}
