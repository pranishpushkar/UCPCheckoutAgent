package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** H3 P8: the classification matrix of 8.1 and 8.2, one test per surviving mutant (see each comment). */
class FailureClassifierMutationTest {

    private final FailureClassifier classifier = new FailureClassifier(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    private static byte[] fixture(String name) {
        try (InputStream in = FailureClassifierMutationTest.class.getResourceAsStream("/merchant/" + name + ".json")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpReply reply(int status, String fixture, String... headers) {
        Map<String, List<String>> h = new java.util.LinkedHashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            h.put(headers[i].toLowerCase(), List.of(headers[i + 1]));
        }
        return new HttpReply(status, h, fixture(fixture));
    }

    // Covers: 8.1, I8 (the cause of a failure is its class and its message). Kills FailureClassifier.classify:70
    // RemoveConditional_EQUAL_IF (a message was dropped from every cause)
    @Test
    void theCause_isTheClassAndTheMessage_orJustTheClass() {
        assertThat(classifier.classify(AttemptOp.GET, new HttpTimeoutException("timed out")))
                .isEqualTo(new CallOutcome.Uncertain<CheckoutView>("HttpTimeoutException: timed out"));
        assertThat(classifier.classify(AttemptOp.GET, new IOException((String) null)))
                .isEqualTo(new CallOutcome.Uncertain<CheckoutView>("IOException"));
    }

    // Covers: 8.1 (a control character in a message is shown as a space; the cause is cut at 200 characters). Kills
    // FailureClassifier.clean:180 ConditionalsBoundary (the cut)
    @Test
    void theCause_isCleanedAndCutAt200Characters() {
        assertThat(classifier.classify(AttemptOp.GET, new IOException("a\nb\tc")))
                .isEqualTo(new CallOutcome.Uncertain<CheckoutView>("IOException: a b c"));

        CallOutcome<CheckoutView> out = classifier.classify(AttemptOp.GET, new IOException("x".repeat(300)));

        assertThat(out).isInstanceOfSatisfying(CallOutcome.Uncertain.class,
                u -> assertThat(u.cause()).isEqualTo("IOException: " + "x".repeat(200)));
    }

    // Covers: 8.1 (only a ConnectException whose cause chain holds an UnresolvedAddressException is NotSent). Kills
    // FailureClassifier.isUnresolvedAddress:78 RemoveConditional_EQUAL_ELSE: any other exception that wraps one is Uncertain
    @Test
    void anUnresolvedAddress_insideAnythingButAConnectException_isUncertain() {
        IOException wrapped = new IOException("wrapper", new UnresolvedAddressException());

        assertThat(classifier.classify(AttemptOp.GET, wrapped)).isInstanceOf(CallOutcome.Uncertain.class);
    }

    private static ConnectException connectWithUnresolvedAt(int depth) {
        Throwable cause = new UnresolvedAddressException();
        for (int i = 1; i < depth; i++) {
            cause = new RuntimeException(cause);
        }
        ConnectException top = new ConnectException("dns");
        top.initCause(cause);
        return top;
    }

    // Covers: 8.1. Kills FailureClassifier.isUnresolvedAddress:82 ConditionalsBoundary and RemoveConditional_ORDER_IF: the cause
    // chain is searched to a depth of 16 links (the top is 0), no further
    @Test
    void theCauseChain_isSearchedToAFixedDepth() {
        assertThat(classifier.classify(AttemptOp.GET, connectWithUnresolvedAt(15))).as("found at depth 15")
                .isInstanceOf(CallOutcome.NotSent.class);
        assertThat(classifier.classify(AttemptOp.GET, connectWithUnresolvedAt(16))).as("not looked for at depth 16")
                .isInstanceOf(CallOutcome.Uncertain.class);
    }

    // Covers: 8.1. Kills FailureClassifier.isUnresolvedAddress:82 Increments (and ORDER_IF): a cause chain that loops back on
    // itself ends, as Uncertain, instead of spinning for ever
    @Test
    void aCyclicCauseChain_isClassified_andEnds() {
        ConnectException a = new ConnectException("a");
        IOException b = new IOException("b");
        a.initCause(b);
        b.initCause(a);

        CallOutcome<CheckoutView> out = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> classifier.classify(AttemptOp.GET, a));

        assertThat(out).isInstanceOf(CallOutcome.Uncertain.class);
    }

    // Covers: 8.1 (402 is a ProtocolError only for a UCP error envelope, and only 402). Kills FailureClassifier.classifyError:112
    // RemoveConditional_EQUAL_IF: a 5xx whose body happens to be an error envelope is still a server failure, Uncertain
    @Test
    void anErrorEnvelope_onAnotherStatusThan402_doesNotMakeAProtocolError() {
        String envelope = "{\"ucp\":{\"status\":\"error\"},\"messages\":[{\"code\":\"x\",\"content\":\"y\"}]}";
        assertThat(CheckoutParser.isErrorEnvelope(envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isTrue();
        assertThat(classifier.classify(AttemptOp.CREATE, new HttpReply(500, Map.of(), envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isEqualTo(new CallOutcome.Uncertain<CheckoutView>("http 500"));
        assertThat(classifier.classify(AttemptOp.COMPLETE, new HttpReply(418, Map.of(), envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isEqualTo(new CallOutcome.Uncertain<CheckoutView>("unexpected http status 418"));
        assertThat(classifier.classify(AttemptOp.COMPLETE, new HttpReply(402, Map.of(), envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .as("402 with the envelope is a ProtocolError").isInstanceOf(CallOutcome.ProtocolError.class);
    }

    // Covers: 8.1 (a 5xx other than 501 is Uncertain; anything else unlisted is "unexpected": a ProtocolError for get, Uncertain
    // otherwise). Kills FailureClassifier.classifyError:124 RemoveConditional_ORDER_IF (both bounds)
    @Test
    void theServerErrorRange_isFiveHundredToFiveNinetyNine_exceptFiveOhOne() {
        for (int status : new int[] {500, 502, 504, 599}) {
            assertThat(classifier.classify(AttemptOp.UPDATE, reply(status, "error_not_found"))).as("status " + status)
                    .isEqualTo(new CallOutcome.Uncertain<CheckoutView>("http " + status));
        }
        for (int status : new int[] {204, 301, 418, 501, 600}) {
            assertThat(classifier.classify(AttemptOp.UPDATE, reply(status, "error_not_found"))).as("status " + status)
                    .isEqualTo(new CallOutcome.Uncertain<CheckoutView>("unexpected http status " + status));
        }
        assertThat(classifier.classify(AttemptOp.GET, reply(418, "error_not_found"))).isEqualTo(
                new CallOutcome.ProtocolError<CheckoutView>(418, "unexpected_status", "HTTP 418", java.util.Optional.empty()));
    }

    // Covers: 8.1 (the error envelope, an `ucp.status: error` body with a 200, is Rejected for a create or an update;
    // anything else with that body is not a usable answer). Kills FailureClassifier.fromBody:138 RemoveConditional_EQUAL_ELSE
    @Test
    void aCreateRejectedEnvelope_isRejected_onlyForACreateOrUpdateAnswered200() {
        assertThat(classifier.classify(AttemptOp.CREATE, reply(200, "create_all_unavailable"))).isInstanceOf(CallOutcome.Rejected.class);
        String why = "invalid response: unexpected create-rejected envelope";
        assertThat(classifier.classify(AttemptOp.CREATE, reply(201, "create_all_unavailable")))
                .isEqualTo(new CallOutcome.Uncertain<CheckoutView>(why));
        assertThat(classifier.classify(AttemptOp.UPDATE, reply(200, "create_all_unavailable")))
                .isInstanceOf(CallOutcome.Rejected.class);
        assertThat(classifier.classify(AttemptOp.UPDATE, reply(201, "create_all_unavailable")))
                .isEqualTo(new CallOutcome.Uncertain<CheckoutView>(why));
        assertThat(classifier.classify(AttemptOp.CANCEL, reply(200, "create_all_unavailable")))
                .isEqualTo(new CallOutcome.Uncertain<CheckoutView>(why));
        assertThat(classifier.classify(AttemptOp.COMPLETE, reply(200, "create_all_unavailable")))
                .isEqualTo(new CallOutcome.Uncertain<CheckoutView>(why));
    }

    // Covers: 8.2 (a Retry-After too large for a long is clamped to one day, like any other long one). Kills
    // FailureClassifier.retryAfter:163 EmptyObjectReturnVals (NO_COVERAGE)
    @Test
    void aRetryAfterBeyondALong_isOneDay() {
        CallOutcome<CheckoutView> out = classifier.classify(AttemptOp.CREATE,
                reply(429, "error_not_found", "Retry-After", "99999999999999999999999"));

        assertThat(out).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.retryAfter()).contains(Duration.ofDays(1)));
    }

    // Covers: 8.2. Shows FailureClassifier.min:175 ConditionalsBoundary is equivalent: a Retry-After of exactly one day is one day
    @Test
    void aRetryAfterOfExactlyOneDay_isOneDay() {
        CallOutcome<CheckoutView> out = classifier.classify(AttemptOp.CREATE, reply(429, "error_not_found", "Retry-After", "86400"));

        assertThat(out).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.retryAfter()).contains(Duration.ofDays(1)));
    }
}
