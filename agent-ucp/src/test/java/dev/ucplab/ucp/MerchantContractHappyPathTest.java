package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.Buyer;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.QuoteShape;
import dev.ucplab.core.merchant.UpdateCheckout;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The whole purchase through {@link UcpHttpGateway} and the real merchant, checked against /_lab/orders (section 14). */
class MerchantContractHappyPathTest extends ContractTestBase {

    // Covers: 2.1, 8.5, decision K (discover: the merchant's real profile through the real transport)
    @Test
    void discover_readsTheRealMerchantProfile() {
        MerchantProfile p = gateway().discover(SHOP);

        assertThat(p.ucpVersion()).isEqualTo(UcpHttpGateway.SUPPORTED_VERSION);
        assertThat(p.capabilities()).contains(UcpHttpGateway.CHECKOUT_CAPABILITY);
        assertThat(p.paymentHandlers()).containsExactly("mock_pay_1");
        assertThat(p.checkoutEndpoint().toString()).isEqualTo(merchant.baseUrl() + "/ucp/v1");
    }

    // Covers: G1, FR2, FR4, I1, I2, 8.4 (create, update to ready, complete and get through the gateway: the merchant
    // accepts every request, and exactly one order exists, with the quoted total)
    @Test
    void createUpdateCompleteGet_endInExactlyOneOrder() {
        UcpHttpGateway gateway = gateway();

        // Create without a buyer: incomplete, with a message about the missing email.
        CheckoutView created = ok(gateway.create(
                new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.empty()), key(MutatingOp.CREATE, 0)));
        assertThat(created.status()).isEqualTo(CheckoutStatus.INCOMPLETE);
        assertThat(created.totalMinor()).isEqualTo(5400);

        // Update with the line ids from the created view and a buyer: ready.
        QuoteShape shape = CheckoutWire.quoteShape(CheckoutWire.responseSummary(created)).orElseThrow();
        UpdateCheckout update = UpdateCheckout.from(List.of(new ProposedLine("item_123", 2)),
                Buyer.ofEmail("alice@example.com"), Optional.of(shape));
        CheckoutView ready = ok(gateway.update(created.id(), update, key(MutatingOp.UPDATE, 0)));
        assertThat(ready.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
        assertThat(ready.id()).isEqualTo(created.id());
        assertThat(ready.lines()).singleElement().satisfies(l -> assertThat(l.lineId()).isEqualTo(created.lines().get(0).lineId()));
        assertThat(ready.buyer().flatMap(Buyer::email)).contains("alice@example.com");
        assertThat(merchant.orders()).isEmpty();

        CheckoutView done = ok(gateway.complete(created.id(), PAY_OK, key(MutatingOp.COMPLETE, 0)));

        assertThat(done.status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(done.order()).isPresent();
        CheckoutView read = ok(gateway.get(created.id()));
        assertThat(read.status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(read.order()).isEqualTo(done.order());
        assertThat(merchant.orders()).singleElement().satisfies(o -> {
            assertThat(o.get("id")).isEqualTo(done.order().orElseThrow().id());
            assertThat(o.get("checkout_id")).isEqualTo(created.id().value());
            assertThat(o.get("buyer_email")).isEqualTo("alice@example.com");
            assertThat(o.get("amount")).isEqualTo(5400L);
        });
    }

    // Covers: FR2, 8.1 (create with a buyer is ready at once; the 201 and the quote's totals are read correctly:
    // subtotal 5000, tax 8% = 400, total 5400, one line at the merchant's price)
    @Test
    void create_withBuyer_isReadyWithTheMerchantsTotals() {
        CallOutcome<CheckoutView> outcome = gateway().create(
                new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("alice@example.com")),
                key(MutatingOp.CREATE, 0));

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.Ok.class, ok -> assertThat(ok.httpStatus()).isEqualTo(201));
        CheckoutView v = ((CallOutcome.Ok<CheckoutView>) outcome).value();
        assertThat(v.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
        assertThat(v.subtotalMinor()).isEqualTo(5000);
        assertThat(v.taxMinor()).isEqualTo(400);
        assertThat(v.totalMinor()).isEqualTo(5400);
        assertThat(v.lines()).singleElement().satisfies(l -> {
            assertThat(l.itemId()).isEqualTo("item_123");
            assertThat(l.quantity()).isEqualTo(2);
            assertThat(l.unitPriceMinor()).isEqualTo(2500);
        });
    }

    // Covers: A.1, 8.1, decision N (create with every item unavailable is Rejected by the real merchant)
    @Test
    void create_withEveryItemUnavailable_isRejected() {
        CallOutcome<CheckoutView> outcome = gateway().create(
                new CreateCheckout(List.of(new ProposedLine("no_such_item", 1)), Optional.of("a@example.com")),
                key(MutatingOp.CREATE, 0));

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.Rejected.class, r -> {
            assertThat(r.httpStatus()).isEqualTo(200);
            assertThat(r.messages()).singleElement().satisfies(m ->
                    assertThat(((dev.ucplab.core.merchant.MerchantMessage) m).code()).isEqualTo("out_of_stock"));
        });
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: A.1 (cancel through the gateway cancels an open session; a completed order is never touched)
    @Test
    void cancel_cancelsAnOpenSession() {
        UcpHttpGateway gateway = gateway();
        CheckoutView created = ok(gateway.create(
                new CreateCheckout(List.of(new ProposedLine("item_456", 1)), Optional.of("a@example.com")),
                key(MutatingOp.CREATE, 0)));

        CheckoutView canceled = ok(gateway.cancel(created.id(), key(MutatingOp.CANCEL, 0)));

        assertThat(canceled.status()).isEqualTo(CheckoutStatus.CANCELED);
        assertThat(ok(gateway.get(created.id())).status()).isEqualTo(CheckoutStatus.CANCELED);
        assertThat(merchant.orders()).isEmpty();
    }
}
