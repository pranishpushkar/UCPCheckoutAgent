package dev.ucplab.ucp;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.merchant.MerchantHarness;
import java.net.URI;
import java.time.Clock;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests (section 14, level 4) run {@link UcpHttpGateway} with the real {@link JdkHttpTransport} against the
 * reference merchant started in this JVM. One merchant per test class; every test starts from a reset merchant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ContractTestBase {

    static final MerchantId SHOP = new MerchantId("shop-1");
    static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    static final IntentId INTENT = new IntentId("intent-1");
    static final FrozenBody PAY_OK = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"));
    static final FrozenBody PAY_DECLINE = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_decline"));

    MerchantHarness merchant;

    @BeforeAll
    void startMerchant() throws Exception {
        merchant = MerchantHarness.start();
    }

    @AfterAll
    void stopMerchant() {
        merchant.close();
    }

    @BeforeEach
    void resetMerchant() {
        merchant.reset();
    }

    static IdempotencyKey key(MutatingOp op, int revision) {
        return IdempotencyKey.derive(INTENT, op, revision);
    }

    /** A gateway for the merchant at {@code baseUrl}, with the real transport and no real sleeping between retries. */
    static UcpHttpGateway gateway(URI baseUrl, UcpConfig config) {
        return new UcpHttpGateway(new MerchantEntry(SHOP, baseUrl, "Shop", MerchantStatus.ACTIVE), config,
                new JdkHttpTransport(config), new CircuitBreakers(config, Clock.systemUTC()), Clock.systemUTC(),
                d -> { }, new SplittableRandom(3), new SequentialIdGenerator("c"));
    }

    UcpHttpGateway gateway() {
        return gateway(merchant.baseUrl(), CONFIG);
    }

    static CheckoutView ok(CallOutcome<CheckoutView> outcome) {
        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        return ((CallOutcome.Ok<CheckoutView>) outcome).value();
    }
}
