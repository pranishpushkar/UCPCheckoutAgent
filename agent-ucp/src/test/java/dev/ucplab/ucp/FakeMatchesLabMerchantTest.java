package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import dev.ucplab.merchant.MerchantHarness;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Section 14 level 2: {@code FaultyMerchantModel} must match the reference merchant's statuses and messages exactly. The
 * same request goes to the real lab merchant (started in this JVM, through {@link UcpHttpGateway}) and to the model, and the
 * answers are compared field by field. H3 P4 fix 1 found a create without a buyer answered by the lab merchant with a
 * {@code missing} message at {@code $.buyer.email} and by the model with none.
 */
class FakeMatchesLabMerchantTest {

    private static final MerchantId SHOP = new MerchantId("shop-1");
    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    private static final IdempotencyKey KEY = IdempotencyKey.derive(new IntentId("intent-1"), MutatingOp.CREATE, 0);
    private static MerchantHarness lab;

    private FaultyMerchantModel model;
    private UcpHttpGateway real;

    @BeforeAll
    static void startLab() throws Exception {
        lab = MerchantHarness.start();
    }

    @AfterAll
    static void stopLab() {
        lab.close();
    }

    @BeforeEach
    void setUp() {
        lab.reset();
        model = new FaultyMerchantModel(SHOP, new MutableClock(Instant.parse("2026-01-01T00:00:00Z")),
                new SequentialIdGenerator("m"));
        real = new UcpHttpGateway(new MerchantEntry(SHOP, lab.baseUrl(), "Shop", MerchantStatus.ACTIVE), CONFIG,
                new JdkHttpTransport(CONFIG), new CircuitBreakers(CONFIG, Clock.systemUTC()), Clock.systemUTC(), d -> { },
                new SplittableRandom(3), new SequentialIdGenerator("c"));
    }

    private static CheckoutView ok(CallOutcome<CheckoutView> outcome) {
        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        return ((CallOutcome.Ok<CheckoutView>) outcome).value();
    }

    // Covers: 14 level 2, A.1 (a create with no buyer: the model answers incomplete with the same status and the same
    // messages, field by field, as the real lab merchant, and no buyer)
    @Test
    void aCreateWithNoBuyer_isAnsweredLikeTheLabMerchant() {
        CreateCheckout noBuyer = new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.empty());

        CheckoutView expected = ok(real.create(noBuyer, KEY));
        CheckoutView actual = ok(model.create(noBuyer, KEY));

        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.messages()).isEqualTo(expected.messages());
        assertThat(actual.buyer()).isEqualTo(expected.buyer());
        assertThat(actual.totalMinor()).isEqualTo(expected.totalMinor());
        assertThat(expected.messages()).as("the reference merchant does say it").isNotEmpty();
    }

    // Covers: 14 level 2, A.1 (a create with a buyer is answered with no messages by both, so the new message is not
    // added to the ready case)
    @Test
    void aCreateWithABuyer_isAnsweredLikeTheLabMerchant() {
        CreateCheckout withBuyer = new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("a@example.com"));

        CheckoutView expected = ok(real.create(withBuyer, KEY));
        CheckoutView actual = ok(model.create(withBuyer, KEY));

        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.messages()).isEqualTo(expected.messages());
    }
}
