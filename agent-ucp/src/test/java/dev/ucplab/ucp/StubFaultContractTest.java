package dev.ucplab.ucp;

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
 * The two fault cases that stay on a test-only stub server after M4 moved the rest to the fault proxy
 * ({@link ProxyFaultContractTest}):
 * <ul>
 *   <li>an unknown status string in an otherwise valid answer: the proxy can only truncate or garble a body, never
 *       rewrite one field of valid JSON;</li>
 *   <li>the headers of every attempt (UCP-Agent, Content-Type, no Authorization) and the exact bytes: by design the
 *       proxy log records no header except Request-Id and Idempotency-Key, and no body (I8).</li>
 * </ul>
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

    // Covers: 8.2, 8.4, decision H, I7 (across four attempts: UCP-Agent from the configuration, a UUID Request-Id,
    // JSON content type, no Authorization, and the same canonical body bytes. The key, the Request-Id per attempt and
    // the bodyHmac are also checked on the real wire in ProxyFaultContractTest)
    @Test
    void everyAttempt_carriesTheUcpHeaders_andNoAuthorization() {
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
    }

    // Covers: 8.4, I7 (the frozen complete bytes arrive unchanged, with a UUID Request-Id; the proxy can show the hash
    // of the bytes, only a stub can show the bytes themselves)
    @Test
    void completeBody_arrivesByteForByte() {
        stub.on(COMPLETE, fixture(200, "complete_completed"));

        gateway().complete(CHK, PAY, COMPLETE_KEY);

        StubMerchant.Seen s = stub.requestsTo(COMPLETE).get(0);
        assertThat(s.body()).isEqualTo(PAY.bytes());
        assertThat(UUID.fromString(s.headers().get("request-id"))).isNotNull();
    }
}
