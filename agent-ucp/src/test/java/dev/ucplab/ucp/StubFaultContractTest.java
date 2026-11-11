package dev.ucplab.ucp;

import static dev.ucplab.ucp.StubMerchant.delay;
import static dev.ucplab.ucp.StubMerchant.drop;
import static dev.ucplab.ucp.StubMerchant.fixture;
import static dev.ucplab.ucp.StubMerchant.reply;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.SessionId;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Faults the real merchant cannot produce, from a test-only stub server (decision R), through {@link UcpHttpGateway} and
 * the real {@link JdkHttpTransport}: every row of 8.1 that needs a misbehaving server, and the headers and bytes of
 * every attempt (8.2, 8.4, I7).
 */
class StubFaultContractTest {

    private static final MerchantId SHOP = new MerchantId("shop-1");
    private static final IntentId INTENT = new IntentId("intent-1");
    private static final IdempotencyKey CREATE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.CREATE, 0);
    private static final IdempotencyKey COMPLETE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.COMPLETE, 0);
    private static final SessionId CHK = new SessionId("chk_1001");
    private static final String COMPLETE = "POST /ucp/v1/checkout-sessions/chk_1001/complete";
    private static final String CREATE = "POST /ucp/v1/checkout-sessions";
    private static final String GET = "GET /ucp/v1/checkout-sessions/chk_1001";
    private static final FrozenBody PAY = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"));
    /** Short timeouts so a "slower than the timeout" test does not take 10 s. */
    private static final UcpConfig CONFIG = new UcpConfig("profile=\"https://agent.example/.well-known/ucp\"",
            Duration.ofSeconds(2), Duration.ofMillis(300), Duration.ofMillis(400), 3, Duration.ofMillis(200),
            Duration.ofSeconds(2), 5, Duration.ofSeconds(30), 1 << 20);

    private StubMerchant stub;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        stub = new StubMerchant();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private UcpHttpGateway gateway() {
        return new UcpHttpGateway(new MerchantEntry(SHOP, stub.baseUrl(), "Shop", MerchantStatus.ACTIVE), CONFIG,
                new JdkHttpTransport(CONFIG), new CircuitBreakers(CONFIG, Clock.systemUTC()), Clock.systemUTC(),
                sleeps::add, new SplittableRandom(5), new SequentialIdGenerator("s"));
    }

    private static CreateCheckout createRequest() {
        return new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("a@example.com"));
    }

    // ---------------------------------------------------------------- drops and delays

    // Covers: 8.1, 8.2, S2 (the merchant takes a create and drops the connection: Uncertain, retried under the same key
    // and bytes, four attempts, each reaching the stub)
    @Test
    void createDroppedAfterTheRequest_isUncertain_andRetriedUnderTheSameKey() {
        stub.on(CREATE, drop());

        CallOutcome<CheckoutView> outcome = gateway().create(createRequest(), CREATE_KEY);

        assertThat(outcome).isInstanceOf(CallOutcome.Uncertain.class);
        List<StubMerchant.Seen> seen = stub.requestsTo(CREATE);
        assertThat(seen).hasSize(4);
        assertThat(seen.stream().map(s -> s.headers().get("idempotency-key")).distinct()).containsExactly(CREATE_KEY.value());
        assertThat(seen.stream().map(s -> new String(s.body())).distinct()).hasSize(1);
    }

    // Covers: 8.1, 8.2, S2 (a complete whose response is dropped is Uncertain and is sent exactly once: the gateway never
    // re-sends it, the orchestrator reconciles)
    @Test
    void completeDroppedAfterTheRequest_isUncertain_andNotResent() {
        stub.on(COMPLETE, drop());

        CallOutcome<CheckoutView> outcome = gateway().complete(CHK, PAY, COMPLETE_KEY);

        assertThat(outcome).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(stub.requestsTo(COMPLETE)).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    // Covers: 8.1, 8.3, S3 (a complete held past its timeout is Uncertain, one request; a get held past it is retried
    // four times and then Uncertain)
    @Test
    void responseSlowerThanTheTimeout_isUncertain() {
        stub.on(COMPLETE, delay(1500, fixture(200, "complete_completed")))
                .on(GET, delay(1500, fixture(200, "get_ready")));
        UcpHttpGateway gateway = gateway();

        assertThat(gateway.complete(CHK, PAY, COMPLETE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(stub.requestsTo(COMPLETE)).hasSize(1);

        assertThat(gateway.get(CHK)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(stub.requestsTo(GET)).hasSize(4);
    }

    // ---------------------------------------------------------------- bad bodies

    // Covers: 8.1, decision B (a garbled 200 body: Uncertain for a mutating call, retried under the same key; for get a
    // ProtocolError, not retried)
    @Test
    void garbledBody_isUncertainForMutatingAndProtocolErrorForGet() {
        stub.on(CREATE, reply(201, "{\"id\":\"chk_1\",\"status\":\"incompl")).on(GET, reply(200, "<html>oops</html>"));
        UcpHttpGateway gateway = gateway();

        assertThat(gateway.create(createRequest(), CREATE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(stub.requestsTo(CREATE)).hasSize(4);

        assertThat(gateway.get(CHK)).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.code()).isEqualTo("invalid_response"));
        assertThat(stub.requestsTo(GET)).hasSize(1);
    }

    // Covers: 8.1, decision B (an unknown status string, and the sixth spec status requires_escalation: Uncertain on
    // complete, one request; ProtocolError on get)
    @Test
    void unknownStatusString_isUncertainOnComplete_andProtocolErrorOnGet() {
        String weird = new String(fixtureBytes("get_ready")).replace("ready_for_complete", "weird");
        String escalation = new String(fixtureBytes("get_ready")).replace("ready_for_complete", "requires_escalation");
        stub.on(COMPLETE, reply(200, weird)).on(GET, reply(200, escalation));
        UcpHttpGateway gateway = gateway();

        assertThat(gateway.complete(CHK, PAY, COMPLETE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(stub.requestsTo(COMPLETE)).hasSize(1);
        assertThat(gateway.get(CHK)).isInstanceOf(CallOutcome.ProtocolError.class);
    }

    private static byte[] fixtureBytes(String name) {
        try (var in = StubFaultContractTest.class.getResourceAsStream("/merchant/" + name + ".json")) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 429, 503, 500

    // Covers: 8.2, decision G (429 or 503 with a short Retry-After on a create: the gateway sleeps that long and retries
    // under the same key, then gets the answer)
    @Test
    void shortRetryAfter_isSleptAndRetried() {
        for (int status : new int[] {429, 503}) {
            sleeps.clear();
            stub.on(CREATE, reply(status, "{\"code\":\"slow_down\",\"content\":\"later\"}", "Retry-After", "1"),
                    fixture(201, "create_ready"));

            CallOutcome<CheckoutView> outcome = gateway().create(createRequest(), CREATE_KEY);

            assertThat(outcome).as("status " + status).isInstanceOf(CallOutcome.Ok.class);
            assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
        }
    }

    // Covers: 8.2, decision G (a Retry-After above 2 s is not slept: the call stops and returns the ProtocolError with
    // retryAfter, after one request)
    @Test
    void longRetryAfter_isReturned() {
        stub.on(GET, reply(503, "{\"code\":\"maintenance\",\"content\":\"back soon\"}", "Retry-After", "10"));

        CallOutcome<CheckoutView> outcome = gateway().get(CHK);

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(503);
            assertThat(e.retryAfter()).contains(Duration.ofSeconds(10));
        });
        assertThat(stub.requestsTo(GET)).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    // Covers: 8.1, 8.2, decision D (complete answered 500, 504, 429 or 503: Uncertain, exactly one request each)
    @Test
    void completeAnswered500Or429Or503_isUncertain_withOneRequest() {
        for (int status : new int[] {500, 504, 429, 503}) {
            stub.on(COMPLETE, reply(status, "{\"code\":\"x\",\"content\":\"y\"}", "Retry-After", "1"));
            int before = stub.requestsTo(COMPLETE).size();

            CallOutcome<CheckoutView> outcome = gateway().complete(CHK, PAY, COMPLETE_KEY);

            assertThat(outcome).as("status " + status).isInstanceOf(CallOutcome.Uncertain.class);
            assertThat(stub.requestsTo(COMPLETE)).as("status " + status).hasSize(before + 1);
        }
        assertThat(sleeps).isEmpty();
    }

    // ---------------------------------------------------------------- headers and bytes of every attempt

    // Covers: 8.2, 8.4, decision H, I7 (across four attempts: UCP-Agent from the configuration, a UUID Request-Id that is
    // different on every attempt, and the same Idempotency-Key and the same canonical body bytes; no Authorization)
    @Test
    void everyAttempt_hasItsOwnRequestId_andTheSameKeyAndBytes() {
        stub.on(CREATE, reply(503, "{\"code\":\"busy\",\"content\":\"x\"}"), reply(503, "{\"code\":\"busy\",\"content\":\"x\"}"),
                reply(503, "{\"code\":\"busy\",\"content\":\"x\"}"), fixture(201, "create_ready"));

        CallOutcome<CheckoutView> outcome = gateway().create(createRequest(), CREATE_KEY);

        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        List<StubMerchant.Seen> seen = stub.requestsTo(CREATE);
        assertThat(seen).hasSize(4).allSatisfy(s -> {
            assertThat(s.headers().get("ucp-agent")).isEqualTo(CONFIG.ucpAgent());
            assertThat(UUID.fromString(s.headers().get("request-id"))).isNotNull();
            assertThat(s.headers().get("idempotency-key")).isEqualTo(CREATE_KEY.value());
            assertThat(s.headers().get("content-type")).isEqualTo("application/json");
            assertThat(s.headers()).doesNotContainKey("authorization");
            assertThat(s.body()).isEqualTo(CheckoutWire.createBody(createRequest()).getBytes());
        });
        assertThat(seen.stream().map(s -> s.headers().get("request-id")).distinct()).hasSize(4);
        assertThat(sleeps).hasSize(3);
    }

    // Covers: 8.4, I7 (a complete retried after NotSent cannot be provoked by a stub that answers, so check the one
    // attempt: the frozen bytes arrive unchanged, with the key, a UUID Request-Id, and no body re-encoding)
    @Test
    void completeBody_arrivesByteForByte() {
        stub.on(COMPLETE, fixture(200, "complete_completed"));

        gateway().complete(CHK, PAY, COMPLETE_KEY);

        StubMerchant.Seen s = stub.requestsTo(COMPLETE).get(0);
        assertThat(s.body()).isEqualTo(PAY.bytes());
        assertThat(s.headers().get("idempotency-key")).isEqualTo(COMPLETE_KEY.value());
        assertThat(UUID.fromString(s.headers().get("request-id"))).isNotNull();
    }
}
