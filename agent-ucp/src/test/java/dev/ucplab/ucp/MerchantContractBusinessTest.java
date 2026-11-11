package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Business outcomes and idempotency, through {@link UcpHttpGateway} against the real merchant (A.1, 8.1, 8.2). Every
 * answer here is HTTP 200 or a protocol error from the real server; the assertions are on what the gateway returns and,
 * for money, on /_lab/orders.
 */
class MerchantContractBusinessTest extends ContractTestBase {

    private SessionId readySession(UcpHttpGateway gateway, String item, int quantity) {
        return ok(gateway.create(new CreateCheckout(List.of(new ProposedLine(item, quantity)),
                Optional.of("alice@example.com")), key(MutatingOp.CREATE, 0))).id();
    }

    private static MerchantMessage only(CheckoutView v) {
        assertThat(v.messages()).hasSize(1);
        return v.messages().get(0);
    }

    // ---------------------------------------------------------------- idempotency (A.1, I1, I7)

    // Covers: A.1, I1, I7 (a replayed complete, same key and same bytes, returns the stored result; one order)
    @Test
    void replayedComplete_returnsTheStoredResult_andCreatesNoSecondOrder() {
        UcpHttpGateway gateway = gateway();
        SessionId id = readySession(gateway, "item_123", 2);

        CheckoutView first = ok(gateway.complete(id, PAY_OK, key(MutatingOp.COMPLETE, 0)));
        CheckoutView replay = ok(gateway.complete(id, PAY_OK, key(MutatingOp.COMPLETE, 0)));

        assertThat(first.status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(replay).isEqualTo(first);
        assertThat(merchant.orders()).hasSize(1);
    }

    // Covers: A.1, I1 (a second complete under a new key on a finished checkout returns it unchanged: no new order)
    @Test
    void completeUnderANewKey_onAFinishedCheckout_createsNoSecondOrder() {
        UcpHttpGateway gateway = gateway();
        SessionId id = readySession(gateway, "item_123", 2);
        CheckoutView first = ok(gateway.complete(id, PAY_OK, key(MutatingOp.COMPLETE, 0)));

        CheckoutView again = ok(gateway.complete(id, PAY_OK, key(MutatingOp.COMPLETE, 1)));

        assertThat(again.order()).isEqualTo(first.order());
        assertThat(merchant.orders()).hasSize(1);
    }

    // Covers: A.1, 8.1 (the same key with a different body is a 409: ProtocolError, not retried)
    @Test
    void sameKeyWithADifferentBody_isProtocolError409() {
        UcpHttpGateway gateway = gateway();
        ok(gateway.create(new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("a@example.com")),
                key(MutatingOp.CREATE, 0)));

        CallOutcome<CheckoutView> outcome = gateway.create(
                new CreateCheckout(List.of(new ProposedLine("item_123", 3)), Optional.of("a@example.com")),
                key(MutatingOp.CREATE, 0));

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("idempotency_key_reuse");
            assertThat(e.retryAfter()).isEmpty();
        });
    }

    // Covers: A.1, 8.1 (an unknown session is a 404 ProtocolError, for get, complete and cancel alike)
    @Test
    void unknownSession_isProtocolError404() {
        UcpHttpGateway gateway = gateway();
        SessionId nope = new SessionId("chk_9999");

        assertThat(gateway.get(nope)).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(404);
            assertThat(e.code()).isEqualTo("not_found");
        });
        assertThat(gateway.complete(nope, PAY_OK, key(MutatingOp.COMPLETE, 0)))
                .isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
        assertThat(gateway.cancel(nope, key(MutatingOp.CANCEL, 0)))
                .isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
        assertThat(merchant.orders()).isEmpty();
    }

    // ---------------------------------------------------------------- business outcomes arrive as Ok with messages

    // Covers: A.1, S5, 8.1 (price_changed: Ok, status incomplete, the warning, the refreshed total; nothing charged)
    @Test
    void priceChanged_arrivesAsOkIncompleteWithAWarning() {
        UcpHttpGateway gateway = gateway();
        SessionId id = readySession(gateway, "item_123", 2);
        merchant.setPrice("item_123", 2600);

        CheckoutView v = ok(gateway.complete(id, PAY_OK, key(MutatingOp.COMPLETE, 0)));

        assertThat(v.status()).isEqualTo(CheckoutStatus.INCOMPLETE);
        assertThat(only(v).type()).isEqualTo("warning");
        assertThat(only(v).code()).isEqualTo("price_changed");
        assertThat(v.totalMinor()).isEqualTo(5616);
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: A.1, S6, 8.1 (out_of_stock at complete: Ok, status unchanged, an error message; nothing charged)
    @Test
    void outOfStockAtComplete_arrivesAsOkReadyWithAnErrorMessage() {
        UcpHttpGateway gateway = gateway();
        SessionId id = readySession(gateway, "item_123", 2);
        merchant.setStock("item_123", 1);

        CheckoutView v = ok(gateway.complete(id, PAY_OK, key(MutatingOp.COMPLETE, 0)));

        assertThat(v.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
        assertThat(only(v).type()).isEqualTo("error");
        assertThat(only(v).code()).isEqualTo("out_of_stock");
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: A.1, 8.1 (quantity_adjusted: a create asking for more than the stock is Ok, quantity cut to the stock,
    // a warning; the fake used to treat this as unavailable)
    @Test
    void quantityAdjusted_arrivesAsOkWithAWarning_andTheReducedQuantity() {
        CheckoutView v = ok(gateway().create(new CreateCheckout(List.of(new ProposedLine("item_789", 4)),
                Optional.of("a@example.com")), key(MutatingOp.CREATE, 0)));

        assertThat(v.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
        assertThat(v.lines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualTo(3));
        assertThat(only(v).type()).isEqualTo("warning");
        assertThat(only(v).code()).isEqualTo("quantity_adjusted");
    }

    // Covers: A.1, A.2, S7, 8.1 (payment_declined: Ok, status ready, an error message; no order)
    @Test
    void paymentDeclined_arrivesAsOkReadyWithAnErrorMessage() {
        UcpHttpGateway gateway = gateway();
        SessionId id = readySession(gateway, "item_123", 1);

        CheckoutView v = ok(gateway.complete(id, PAY_DECLINE, key(MutatingOp.COMPLETE, 0)));

        assertThat(v.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
        assertThat(only(v).type()).isEqualTo("error");
        assertThat(only(v).code()).isEqualTo("payment_declined");
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: A.1, A.3, 7.2 (while a charge is held: update answers checkout_busy, a second complete answers
    // complete_in_progress, get shows complete_in_progress; afterwards the one order exists)
    @Test
    void whileAChargeIsHeld_busyAndInProgressAnswersArrive() throws Exception {
        UcpHttpGateway gateway = gateway();
        SessionId id = readySession(gateway, "item_123", 2);
        merchant.setPaymentLatencyMs(1200);
        AtomicReference<CallOutcome<CheckoutView>> held = new AtomicReference<>();
        Thread charging = Thread.ofVirtual().start(() -> held.set(gateway.complete(id, PAY_OK, key(MutatingOp.COMPLETE, 0))));
        awaitStatus(gateway, id, CheckoutStatus.COMPLETE_IN_PROGRESS);

        CheckoutView busy = ok(gateway.update(id,
                UpdateCheckout.of(List.of(new ProposedLine("item_123", 1)), Optional.of("alice@example.com")),
                key(MutatingOp.UPDATE, 0)));
        CheckoutView inProgress = ok(gateway.complete(id, PAY_OK, key(MutatingOp.COMPLETE, 1)));
        CheckoutView read = ok(gateway.get(id));

        assertThat(only(busy).code()).isEqualTo("checkout_busy");
        assertThat(only(busy).type()).isEqualTo("error");
        assertThat(only(inProgress).code()).isEqualTo("complete_in_progress");
        assertThat(inProgress.status()).isEqualTo(CheckoutStatus.COMPLETE_IN_PROGRESS);
        assertThat(read.status()).isEqualTo(CheckoutStatus.COMPLETE_IN_PROGRESS);
        assertThat(merchant.orders()).isEmpty();

        charging.join(Duration.ofSeconds(5));
        assertThat(held.get()).isInstanceOf(CallOutcome.Ok.class);
        assertThat(ok(held.get()).status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(merchant.orders()).hasSize(1);
    }

    private static void awaitStatus(UcpHttpGateway gateway, SessionId id, CheckoutStatus wanted) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (ok(gateway.get(id)).status() == wanted) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("session never reached " + wanted);
    }

    // ---------------------------------------------------------------- network and concurrency

    // Covers: 8.1, 8.5 (a merchant that cannot be reached for discovery gets no call at all: NotSent for every call; no
    // order, no state. Classification of a refused connection itself is in FailureClassifierTest)
    @Test
    void connectionRefused_isNotSent() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        UcpHttpGateway gateway = gateway(URI.create("http://127.0.0.1:" + closed), CONFIG);

        assertThat(gateway.create(new CreateCheckout(List.of(new ProposedLine("item_123", 1)), Optional.empty()),
                key(MutatingOp.CREATE, 0))).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(gateway.complete(new SessionId("chk_1001"), PAY_OK, key(MutatingOp.COMPLETE, 0)))
                .isInstanceOf(CallOutcome.NotSent.class);
        assertThat(gateway.get(new SessionId("chk_1001"))).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: S14, I1, A.1 (24 concurrent completes under one key, through 24 gateway calls on separate threads, give
    // exactly one order, and every caller gets the same completed answer)
    @Test
    void twentyFourConcurrentCompletes_underOneKey_giveExactlyOneOrder() throws Exception {
        UcpHttpGateway gateway = gateway();
        SessionId id = readySession(gateway, "item_123", 2);
        merchant.setPaymentLatencyMs(200);
        IdempotencyKey key = key(MutatingOp.COMPLETE, 0);
        List<CallOutcome<CheckoutView>> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 24; i++) {
            threads.add(Thread.ofVirtual().start(() -> outcomes.add(gateway.complete(id, PAY_OK, key))));
        }
        for (Thread t : threads) {
            t.join(Duration.ofSeconds(15));
        }

        assertThat(outcomes).hasSize(24);
        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(CallOutcome.Ok.class));
        assertThat(outcomes.stream().map(o -> ((CallOutcome.Ok<CheckoutView>) o).value().order()).distinct()).hasSize(1);
        assertThat(merchant.orders()).hasSize(1);
    }

    // Covers: S14, I1 (24 concurrent completes under 24 different keys on one session: the session can produce at most
    // one order whatever the keys; the others see the finished or in-progress checkout)
    @Test
    void twentyFourConcurrentCompletes_underDifferentKeys_stillGiveOneOrder() throws Exception {
        UcpHttpGateway gateway = gateway();
        SessionId id = readySession(gateway, "item_123", 2);
        merchant.setPaymentLatencyMs(200);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 24; i++) {
            int revision = i;
            threads.add(Thread.ofVirtual().start(() -> gateway.complete(id, PAY_OK, key(MutatingOp.COMPLETE, revision))));
        }
        for (Thread t : threads) {
            t.join(Duration.ofSeconds(15));
        }

        assertThat(merchant.orders()).hasSize(1);
    }
}
