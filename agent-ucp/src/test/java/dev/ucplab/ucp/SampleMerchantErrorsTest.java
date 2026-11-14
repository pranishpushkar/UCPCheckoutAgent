package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.SessionId;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The sample merchant's refusals (docs/interop/fixtures 26, 31, 33, 35, 36) are business refusals, matched case-insensitively
 * against our lab's lower-case codes, and are never an outage, never {@code Uncertain}, never retried (8.1, 8.2, H1 C4).
 * Before this commit: 26 (409) was a {@code ProtocolError} with the code {@code unknown} (the envelope's code sits in
 * {@code messages[]}, not at the top), 31 (402) was {@code Uncertain}, 33 and 35 and 36 were {@code ProtocolError} with the
 * code {@code unknown}.
 */
class SampleMerchantErrorsTest {

    private static final MerchantId SAMPLE = new MerchantId("sample");
    private static final MerchantEntry ENTRY = new MerchantEntry(SAMPLE,
            URI.create("http://127.0.0.1:" + InteropFixtures.PORT), "Sample", MerchantStatus.ACTIVE);
    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    private static final IntentId INTENT = new IntentId("intent-1");
    private static final IdempotencyKey CREATE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.CREATE, 0);
    private static final IdempotencyKey COMPLETE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.COMPLETE, 0);
    private static final SessionId SESSION = new SessionId("<id-59>");

    private final TestClock clock = new TestClock();
    private final CircuitBreakers breakers = new CircuitBreakers(CONFIG, clock);
    private final List<HttpCall> calls = new ArrayList<>();

    private UcpHttpGateway gateway(HttpReply answer) {
        HttpTransport t = call -> {
            calls.add(call);
            if (call.uri().getPath().equals("/.well-known/ucp")) {
                return InteropFixtures.reply("01-discovery");
            }
            return answer;
        };
        return new UcpHttpGateway(ENTRY, CONFIG, t, breakers, clock, d -> { }, new SplittableRandom(3),
                new SequentialIdGenerator("r"), Set.of(), "mock_payment_handler", Set.of("2026-04-08"));
    }

    private long sent(String method) {
        return calls.stream().filter(c -> c.method().equals(method)).count();
    }

    private CallOutcome<CheckoutView> complete(HttpReply answer) {
        return gateway(answer).complete(SESSION,
                CheckoutWire.completeBody(PaymentInstrument.sampleCard("instr_spike", "tok")), COMPLETE_KEY);
    }

    // Covers: 8.1, 8.2, 7.2 (fixture 26: 409 OUT_OF_STOCK on complete is the same answer our lab gives for out_of_stock:
    // Ok, the session ready_for_complete, one error message with the lower-case code out_of_stock, so the orchestrator
    // ends FAILED_NOT_CHARGED out_of_stock; sent once, no retry, the breaker stays closed)
    @Test
    void completeAnswered409OutOfStock_isTheLabsOutOfStock() {
        CallOutcome<CheckoutView> out = complete(InteropFixtures.reply("26-stock-complete-sold-out"));

        assertThat(out).isInstanceOfSatisfying(CallOutcome.Ok.class, ok -> {
            assertThat(ok.httpStatus()).isEqualTo(409);
            CheckoutView v = (CheckoutView) ok.value();
            assertThat(v.id()).isEqualTo(SESSION);
            assertThat(v.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
            assertThat(v.messages()).singleElement().satisfies(m -> {
                assertThat(m.type()).isEqualTo("error");
                assertThat(m.code()).isEqualTo("out_of_stock");
            });
        });
        assertThat(sent("POST")).isEqualTo(1);
        assertThat(breakers.forMerchant(SAMPLE).state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // Covers: 8.1, 8.2, 7.2 (fixture 31: 402 INSUFFICIENT_FUNDS on complete is a decline: Ok, ready_for_complete, the
    // code payment_declined that our lab uses, the merchant's own words kept as the content; the orchestrator ends
    // FAILED_NOT_CHARGED payment_declined; not Uncertain, not retried)
    @Test
    void completeAnswered402InsufficientFunds_isADecline() {
        CallOutcome<CheckoutView> out = complete(InteropFixtures.reply("31-decline-complete-fail-token"));

        assertThat(out).isInstanceOfSatisfying(CallOutcome.Ok.class, ok -> {
            assertThat(ok.httpStatus()).isEqualTo(402);
            CheckoutView v = (CheckoutView) ok.value();
            assertThat(v.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
            assertThat(v.messages()).singleElement().satisfies(m -> {
                assertThat(m.type()).isEqualTo("error");
                assertThat(m.code()).isEqualTo("payment_declined");
                assertThat(m.content()).contains("Insufficient Funds");
            });
        });
        assertThat(sent("POST")).isEqualTo(1);
        assertThat(breakers.forMerchant(SAMPLE).state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // Covers: 8.1 (a 402 or 409 on complete whose code we do not know stays what it was: a 409 a ProtocolError with the
    // code in lower case, a 402 without a UCP envelope Uncertain; only known business codes become an Ok)
    @Test
    void unknownRefusalsOnComplete_stayAsTheyWere() {
        HttpReply conflict = new HttpReply(409, Map.of(), ("{\"ucp\":{\"status\":\"error\",\"version\":\"2026-04-08\"},"
                + "\"messages\":[{\"type\":\"error\",\"code\":\"IDEMPOTENCY_CONFLICT\",\"content\":\"x\"}]}")
                .getBytes(StandardCharsets.UTF_8));
        HttpReply bare402 = new HttpReply(402, Map.of(), "pay up".getBytes(StandardCharsets.UTF_8));

        assertThat(complete(conflict)).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("idempotency_conflict");
        });
        assertThat(complete(bare402)).isInstanceOf(CallOutcome.Uncertain.class);
    }

    // Covers: 8.1, 8.2 (fixture 33: 404 RESOURCE_NOT_FOUND on a read is a ProtocolError with the lower-case code, sent
    // once, not an outage: the breaker stays closed)
    @Test
    void getAnswered404_isAProtocolError_notAnOutage() {
        CallOutcome<CheckoutView> out = gateway(InteropFixtures.reply("33-get-unknown-id")).get(new SessionId("does-not-exist"));

        assertThat(out).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(404);
            assertThat(e.code()).isEqualTo("resource_not_found");
            assertThat(e.content()).contains("not found");
        });
        assertThat(sent("GET")).isEqualTo(2);    // the discovery and the read: no retry of the read
        assertThat(breakers.forMerchant(SAMPLE).state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // Covers: 8.1, 8.2, 26.2 (fixtures 35 and 36: 422 VERSION_UNSUPPORTED on a create is a ProtocolError with the
    // lower-case code and the merchant's own version in the content, sent once, no outage)
    @Test
    void createAnswered422VersionUnsupported_isAProtocolError_notAnOutage() {
        for (String fixture : List.of("35-version-2026-08-25", "36-version-2099-01-01")) {
            calls.clear();
            CallOutcome<CheckoutView> out = gateway(InteropFixtures.reply(fixture)).create(
                    new CreateCheckout(List.of(new ProposedLine("bouquet_roses", 1)), Optional.empty()), CREATE_KEY);

            assertThat(out).as(fixture).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
                assertThat(e.httpStatus()).isEqualTo(422);
                assertThat(e.code()).isEqualTo("version_unsupported");
                assertThat(e.content()).contains("2026-04-08");
            });
            assertThat(sent("POST")).as(fixture).isEqualTo(1);
            assertThat(breakers.forMerchant(SAMPLE).state()).isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    // Covers: 8.1 (the classifier alone: the same replies, op by op, with the codes in lower case)
    @Test
    void theClassifier_readsTheEnvelopesMessageCode_inLowerCase() {
        FailureClassifier c = new FailureClassifier(clock);

        assertThat(c.classify(AttemptOp.GET, InteropFixtures.reply("33-get-unknown-id")))
                .isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> assertThat(e.code()).isEqualTo("resource_not_found"));
        assertThat(c.classify(AttemptOp.COMPLETE, InteropFixtures.reply("31-decline-complete-fail-token")))
                .isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(402);
                    assertThat(e.code()).isEqualTo("insufficient_funds");
                });
    }
}
