package dev.ucplab.ucp;

import static dev.ucplab.ucp.ProxyFixture.COMPLETE;
import static dev.ucplab.ucp.ProxyFixture.CREATE;
import static dev.ucplab.ucp.ProxyFixture.SESSION;
import static dev.ucplab.ucp.ProxyFixture.rule;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.faultproxy.Action;
import dev.ucplab.faultproxy.LogEntry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The faults the merchant cannot produce on its own, now made by the real fault proxy in front of the real merchant
 * (section 14, level 4, from M4), through {@link UcpHttpGateway} and the real {@link JdkHttpTransport}. These replace the
 * stub-server cases of M2. The proxy log is the wire evidence (count, key, {@code bodyHmac}, whether the merchant got
 * it) and {@code /_lab/orders} is the ground truth for whether anyone was charged.
 */
class ProxyFaultContractTest extends ContractTestBase {

    private static final IdempotencyKey CREATE_KEY = key(MutatingOp.CREATE, 0);
    private static final IdempotencyKey COMPLETE_KEY = key(MutatingOp.COMPLETE, 0);
    /** Short timeouts so a "slower than the timeout" case does not take 10 s. */
    private static final UcpConfig SHORT = new UcpConfig("profile=\"https://agent.example/.well-known/ucp\"",
            Duration.ofSeconds(2), Duration.ofMillis(300), Duration.ofMillis(400), 3, Duration.ofMillis(200),
            Duration.ofSeconds(2), 5, Duration.ofSeconds(30), 1 << 20);

    private ProxyFixture proxy;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void startProxy() throws Exception {
        proxy = new ProxyFixture(merchant.baseUrl());
        sleeps.clear();
    }

    @AfterEach
    void stopProxy() {
        proxy.close();
    }

    /** A gateway to the proxy, with its own breakers, that records the sleeps it asks for instead of sleeping. */
    @Override
    UcpHttpGateway gateway() {
        // The sleeper moves the clock too, so a 429's Retry-After that the rate limiter holds has passed when it retries.
        SleepClock clock = new SleepClock();
        return new UcpHttpGateway(new MerchantEntry(SHOP, proxy.baseUrl(), "Shop", MerchantStatus.ACTIVE), SHORT,
                new JdkHttpTransport(SHORT), new CircuitBreakers(SHORT, clock), clock,
                clock.recordingInto(sleeps), new SplittableRandom(5), new SequentialIdGenerator("p"));
    }

    private static CreateCheckout createRequest() {
        return new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("a@example.com"));
    }

    private static byte[] createBytes() {
        return CheckoutWire.createBody(createRequest()).getBytes(StandardCharsets.UTF_8);
    }

    /** A real session at the merchant, created through the proxy with no rules installed. */
    private SessionId session() {
        return ok(gateway().create(createRequest(), CREATE_KEY)).id();
    }

    private static boolean merchantAnswered2xx(LogEntry e) {
        return e.merchantStatus().isPresent() && e.merchantStatus().getAsInt() / 100 == 2;
    }

    // ---------------------------------------------------------------- drops

    // Covers: 8.1, 8.2, S1 (the connection is closed before forwarding on every create: Uncertain, four attempts under
    // the same key and bytes, none reaching the merchant; no order)
    @Test
    void createClosedBeforeForwarding_isUncertain_andRetriedUnderTheSameKey() {
        proxy.rules(rule("close-create", "POST", CREATE, Action.FailBeforeForward.closeConnection()));

        CallOutcome<CheckoutView> outcome = gateway().create(createRequest(), CREATE_KEY);

        assertThat(outcome).isInstanceOf(CallOutcome.Uncertain.class);
        List<LogEntry> creates = proxy.to("POST", CREATE);
        assertThat(creates).hasSize(4).allSatisfy(e -> {
            assertThat(e.forwards()).isZero();
            assertThat(e.idempotencyKey()).contains(CREATE_KEY.value());
            assertThat(e.bodyHmac()).contains(proxy.hmac(createBytes()));
        });
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: 8.1, 8.2, A.1 (the merchant takes every create and the answer is dropped: Uncertain, four attempts under
    // the same key and bytes, each answered by the merchant; a later create under the key gets the stored session)
    @Test
    void createDroppedAfterTheMerchantTookIt_isUncertain_andTheKeyReplaysTheSession() {
        proxy.rules(rule("drop-create", "POST", CREATE, new Action.ForwardThenDropResponse()));

        CallOutcome<CheckoutView> outcome = gateway().create(createRequest(), CREATE_KEY);

        assertThat(outcome).isInstanceOf(CallOutcome.Uncertain.class);
        List<LogEntry> creates = proxy.to("POST", CREATE);
        assertThat(creates).hasSize(4).allSatisfy(e -> {
            assertThat(e.forwards()).isEqualTo(1);
            assertThat(merchantAnswered2xx(e)).isTrue();
            assertThat(e.idempotencyKey()).contains(CREATE_KEY.value());
        });
        assertThat(creates).extracting(e -> e.bodyHmac().orElseThrow()).containsOnly(proxy.hmac(createBytes()));

        proxy.clear();
        CheckoutView replayed = ok(gateway().create(createRequest(), CREATE_KEY));
        CheckoutView again = ok(gateway().create(createRequest(), CREATE_KEY));
        assertThat(again.id()).isEqualTo(replayed.id());
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: 8.1, 8.2, S2, I1 (a complete whose answer is dropped after the merchant charged: Uncertain, sent exactly
    // once, and the merchant has exactly one order for that session)
    @Test
    void completeDroppedAfterTheMerchantCharged_isUncertain_sentOnce_oneOrder() {
        SessionId id = session();
        proxy.rules(rule("drop-complete", "POST", COMPLETE, new Action.ForwardThenDropResponse()));

        CallOutcome<CheckoutView> outcome = gateway().complete(id, PAY_OK, COMPLETE_KEY);

        assertThat(outcome).isInstanceOf(CallOutcome.Uncertain.class);
        List<LogEntry> completes = proxy.to("POST", COMPLETE);
        assertThat(completes).hasSize(1);
        assertThat(completes.getFirst().forwards()).isEqualTo(1);
        assertThat(completes.getFirst().merchantStatus()).isEqualTo(OptionalInt.of(200));
        assertThat(sleeps).isEmpty();
        List<Map<String, Object>> orders = merchant.orders();
        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst()).containsEntry("checkout_id", id.value());
    }

    // ---------------------------------------------------------------- slow answers

    // Covers: 8.1, 8.3, S3, I1 (a complete held past its timeout after the merchant charged: Uncertain, one request,
    // one order)
    @Test
    void completeHeldPastTheTimeout_isUncertain_oneRequest_oneOrder() {
        SessionId id = session();
        proxy.rules(rule("hold-complete", "POST", COMPLETE, new Action.ForwardThenDelayResponse(1000)));

        CallOutcome<CheckoutView> outcome = gateway().complete(id, PAY_OK, COMPLETE_KEY);

        assertThat(outcome).isInstanceOf(CallOutcome.Uncertain.class);
        List<LogEntry> completes = proxy.to("POST", COMPLETE);
        assertThat(completes).hasSize(1);
        assertThat(completes.getFirst().merchantStatus()).isEqualTo(OptionalInt.of(200));
        assertThat(merchant.orders()).hasSize(1);
    }

    // Covers: 8.1, 8.3 (a get held past the request timeout is retried, four requests in all, then Uncertain)
    @Test
    void getHeldPastTheTimeout_isRetried_thenUncertain() {
        SessionId id = session();
        proxy.rules(rule("hold-get", "GET", SESSION, new Action.Delay(1000)));

        CallOutcome<CheckoutView> outcome = gateway().get(id);

        assertThat(outcome).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(proxy.to("GET", SESSION)).hasSize(4);
        assertThat(merchant.orders()).isEmpty();
    }

    // ---------------------------------------------------------------- bad bodies

    // Covers: 8.1, decision B (a truncated create answer: Uncertain for a mutating call, retried under the same key)
    @Test
    void truncatedCreateAnswer_isUncertain_andRetried() {
        proxy.rules(rule("truncate-create", "POST", CREATE, new Action.CorruptResponse(Action.CorruptResponse.Kind.TRUNCATE)));

        assertThat(gateway().create(createRequest(), CREATE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);

        assertThat(proxy.to("POST", CREATE)).hasSize(4).allSatisfy(e -> assertThat(merchantAnswered2xx(e)).isTrue());
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: 8.1, decision B (a garbled get answer: ProtocolError invalid_response, not retried)
    @Test
    void garbledGetAnswer_isProtocolError_notRetried() {
        SessionId id = session();
        proxy.rules(rule("garble-get", "GET", SESSION, new Action.CorruptResponse(Action.CorruptResponse.Kind.GARBLE)));

        CallOutcome<CheckoutView> outcome = gateway().get(id);

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.code()).isEqualTo("invalid_response"));
        assertThat(proxy.to("GET", SESSION)).hasSize(1);
        assertThat(merchant.orders()).isEmpty();
    }

    // ---------------------------------------------------------------- 429, 503, 500

    // Covers: 8.2, decision G (429 or 503 with a short Retry-After on a create: the gateway sleeps that long and retries
    // under the same key; the first attempt never reached the merchant)
    @Test
    void shortRetryAfter_isSleptAndRetried() {
        for (int status : new int[] {429, 503}) {
            sleeps.clear();
            proxy.rules(rule("busy-once", "POST", CREATE,
                    new Action.FailBeforeForward(OptionalInt.of(status), false, OptionalInt.of(1))).withTimes(1));
            int before = proxy.to("POST", CREATE).size();

            CallOutcome<CheckoutView> outcome = gateway().create(createRequest(), CREATE_KEY);

            assertThat(outcome).as("status " + status).isInstanceOf(CallOutcome.Ok.class);
            assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
            List<LogEntry> creates = proxy.to("POST", CREATE).subList(before, before + 2);
            assertThat(creates.get(0).forwarded()).isFalse();
            assertThat(creates.get(1).forwarded()).isTrue();
            assertThat(proxy.to("POST", CREATE)).hasSize(before + 2);
        }
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: 8.2, decision G (a Retry-After above 2 s is not slept: the call stops with the ProtocolError and its
    // retryAfter, after one request)
    @Test
    void longRetryAfter_isReturned() {
        SessionId id = session();
        proxy.rules(rule("maintenance", "GET", SESSION,
                new Action.FailBeforeForward(OptionalInt.of(503), false, OptionalInt.of(10))));

        CallOutcome<CheckoutView> outcome = gateway().get(id);

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(503);
            assertThat(e.retryAfter()).contains(Duration.ofSeconds(10));
        });
        assertThat(proxy.to("GET", SESSION)).hasSize(1);
        assertThat(sleeps).isEmpty();
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: 8.1, 8.2, decision D (complete answered 500, 504, 429 or 503: Uncertain, exactly one request each, never
    // resent by the gateway; nothing reached the merchant, so no order)
    @Test
    void completeAnswered500Or504Or429Or503_isUncertain_withOneRequest() {
        SessionId id = session();
        for (int status : new int[] {500, 504, 429, 503}) {
            proxy.rules(rule("fail-complete", "POST", COMPLETE,
                    new Action.FailBeforeForward(OptionalInt.of(status), false, OptionalInt.of(1))));
            int before = proxy.to("POST", COMPLETE).size();

            CallOutcome<CheckoutView> outcome = gateway().complete(id, PAY_OK, COMPLETE_KEY);

            assertThat(outcome).as("status " + status).isInstanceOf(CallOutcome.Uncertain.class);
            assertThat(proxy.to("POST", COMPLETE)).as("status " + status).hasSize(before + 1);
        }
        assertThat(sleeps).isEmpty();
        assertThat(proxy.to("POST", COMPLETE)).noneMatch(LogEntry::forwarded);
        assertThat(merchant.orders()).isEmpty();
    }

    // ---------------------------------------------------------------- keys and bytes of every attempt

    // Covers: 8.2, 8.4, decision H, I7 (across four attempts: a different Request-Id on every attempt, the same
    // Idempotency-Key and the same body bytes, by bodyHmac; only the last reaches the merchant)
    @Test
    void everyAttempt_hasItsOwnRequestId_andTheSameKeyAndBytes() {
        proxy.rules(rule("busy-thrice", "POST", CREATE, Action.FailBeforeForward.status(503)).withTimes(3));

        CallOutcome<CheckoutView> outcome = gateway().create(createRequest(), CREATE_KEY);

        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        List<LogEntry> creates = proxy.to("POST", CREATE);
        assertThat(creates).hasSize(4).allSatisfy(e -> {
            assertThat(e.idempotencyKey()).contains(CREATE_KEY.value());
            assertThat(e.bodyHmac()).contains(proxy.hmac(createBytes()));
        });
        assertThat(creates).extracting(e -> e.requestId().orElseThrow()).doesNotHaveDuplicates();
        assertThat(creates).extracting(LogEntry::forwarded).containsExactly(false, false, false, true);
        assertThat(sleeps).hasSize(3);
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: 8.4, I7 (the frozen complete bytes reach the merchant unchanged, under the complete key; one order)
    @Test
    void completeBody_arrivesByteForByte() {
        SessionId id = session();

        CheckoutView done = ok(gateway().complete(id, PAY_OK, COMPLETE_KEY));

        assertThat(done.status()).isEqualTo(CheckoutStatus.COMPLETED);
        LogEntry complete = proxy.to("POST", COMPLETE).getFirst();
        assertThat(complete.bodyHmac()).contains(proxy.hmac(PAY_OK.bytes()));
        assertThat(complete.idempotencyKey()).contains(COMPLETE_KEY.value());
        assertThat(merchant.orders()).hasSize(1);
        assertThat(merchant.orders().getFirst()).containsEntry("checkout_id", id.value());
    }
}
