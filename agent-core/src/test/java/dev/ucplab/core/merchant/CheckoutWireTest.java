package dev.ucplab.core.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.ProposedLine;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CheckoutWireTest {

    // Covers: 8.4, I7 (equal requests give byte-identical canonical bodies)
    @Test
    void createBody_isCanonical_andEqualForEqualRequests() {
        CreateCheckout a = new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("a@example.com"));
        CreateCheckout b = new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("a@example.com"));

        assertThat(CheckoutWire.createBody(a)).isEqualTo(CheckoutWire.createBody(b)).isEqualTo(
                "{\"buyer\":{\"email\":\"a@example.com\"},\"line_items\":[{\"item\":{\"id\":\"item_123\"},\"quantity\":2}]}");
    }

    // Covers: 7.4, 8.4, A.2, M2 decision M (the frozen complete body carries the instrument in payment.instruments[],
    // as the 2026-08-25 schema requires; its token can be read back). Regression: it was once emitted as payment_data.
    @Test
    void completeBody_carriesInstrument_andTokenReadsBack() {
        FrozenBody body = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"));

        assertThat(body.json()).isEqualTo("{\"payment\":{\"instruments\":[{\"credential\":{\"token\":\"tok_ok\","
                + "\"type\":\"MOCK\"},\"handler_id\":\"mock_pay_1\",\"id\":\"pi_1\",\"type\":\"mock_card\"}]}}");
        assertThat(body.json()).doesNotContain("payment_data");
        assertThat(CheckoutWire.paymentToken(body)).contains("tok_ok");
        assertThat(CheckoutWire.paymentToken(new FrozenBody("{\"x\":1}"))).isEmpty();
        assertThat(CheckoutWire.paymentToken(new FrozenBody("not json"))).isEmpty();
    }

    // Covers: I8 (the response summary and the instrument's toString never carry the credential)
    @Test
    void responseSummaryAndInstrument_neverContainToken() {
        PaymentInstrument card = PaymentInstrument.mockCard("pi_1", "tok_secret_canary");
        CheckoutView view = new CheckoutView(new SessionId("cs_1"), CheckoutStatus.COMPLETED, "USD",
                List.of(new LineView("item_123", 1, 2500)), 2500, 200, 2700, Optional.of(new OrderRef("ord_1")),
                List.of());

        assertThat(card.toString()).doesNotContain("tok_secret_canary");
        assertThat(CheckoutWire.responseSummary(view)).isEqualTo("{\"currency\":\"USD\",\"id\":\"cs_1\","
                + "\"lines\":[{\"item_id\":\"item_123\",\"quantity\":1,\"unit_price_minor\":2500}],"
                + "\"message_codes\":[],\"order_id\":\"ord_1\",\"status\":\"completed\",\"total_minor\":2700}");
    }

    private static final UpdateCheckout UPDATE = new UpdateCheckout(
            List.of(new UpdateLine("item_123", 2, Optional.of("li_1")), new UpdateLine("item_456", 1, Optional.empty())),
            Optional.of(new Buyer(Optional.of("a@example.com"), Optional.of("Ada"), Optional.of("Lovelace"),
                    Optional.of("+15550100"))));

    // Covers: 8.4, decision L (an update is a full replacement: it carries the line ids from the last view and every
    // buyer field, in canonical JSON)
    @Test
    void updateBody_carriesLineIdsAndAllBuyerFields() {
        assertThat(CheckoutWire.updateBody(UPDATE)).isEqualTo("{\"buyer\":{\"email\":\"a@example.com\","
                + "\"first_name\":\"Ada\",\"last_name\":\"Lovelace\",\"phone_number\":\"+15550100\"},"
                + "\"line_items\":[{\"id\":\"li_1\",\"item\":{\"id\":\"item_123\"},\"quantity\":2},"
                + "{\"item\":{\"id\":\"item_456\"},\"quantity\":1}]}");
    }

    // Covers: 8.4, I7 (equal updates give byte-identical bodies; absent ids and buyer fields are left out, never null)
    @Test
    void updateBody_leavesOutAbsentFields_andIsDeterministic() {
        UpdateCheckout u = UpdateCheckout.of(List.of(new ProposedLine("item_123", 2)), Optional.of("a@example.com"));

        assertThat(CheckoutWire.updateBody(u)).isEqualTo(CheckoutWire.updateBody(
                UpdateCheckout.of(List.of(new ProposedLine("item_123", 2)), Optional.of("a@example.com"))))
                .isEqualTo("{\"buyer\":{\"email\":\"a@example.com\"},"
                        + "\"line_items\":[{\"item\":{\"id\":\"item_123\"},\"quantity\":2}]}");
    }

    // Covers: decision L (the line ids and buyer of the last view are read back from the stored response summary)
    @Test
    void quoteShape_readsLineIdsAndBuyer_fromResponseSummary() {
        CheckoutView view = new CheckoutView(new SessionId("cs_1"), CheckoutStatus.READY_FOR_COMPLETE, "USD",
                List.of(new LineView("item_123", 2, 2500, Optional.of("li_1")), new LineView("item_456", 1, 1200)),
                6200, 496, 6696, Optional.empty(),
                Optional.of(new Buyer(Optional.of("a@example.com"), Optional.of("Ada"), Optional.empty(),
                        Optional.empty())),
                List.of());

        QuoteShape shape = CheckoutWire.quoteShape(CheckoutWire.responseSummary(view)).orElseThrow();

        assertThat(shape.lines()).containsExactly(new LineView("item_123", 2, 2500, Optional.of("li_1")),
                new LineView("item_456", 1, 1200));
        assertThat(shape.buyer()).contains(new Buyer(Optional.of("a@example.com"), Optional.of("Ada"),
                Optional.empty(), Optional.empty()));
    }

    // Covers: decision L (a summary with no lines, or text that is not a summary, gives no shape; never throws)
    @Test
    void quoteShape_isEmpty_forSummaryWithoutLinesOrGarbage() {
        assertThat(CheckoutWire.quoteShape("{\"cause\":\"x\",\"outcome\":\"uncertain\"}")).isEmpty();
        assertThat(CheckoutWire.quoteShape("not json")).isEmpty();
        assertThat(CheckoutWire.quoteShape("{\"lines\":[{\"quantity\":1}]}")).isEmpty();
    }

    // Covers: 19, 8.4, H1 C3 (the sample merchant's card-token instrument has the shape the sample accepted in
    // docs/interop/fixtures/08-happy-complete.json: handler mock_payment_handler, type card, credential type token)
    @Test
    void completeBody_ofTheSampleCard_hasTheShapeOfFixture08() {
        FrozenBody body = CheckoutWire.completeBody(PaymentInstrument.sampleCard("instr_spike", "t0k"));

        assertThat(body.json()).isEqualTo("{\"payment\":{\"instruments\":[{\"credential\":{\"token\":\"t0k\","
                + "\"type\":\"token\"},\"handler_id\":\"mock_payment_handler\",\"id\":\"instr_spike\",\"type\":\"card\"}]}}");
        assertThat(CheckoutWire.paymentToken(body)).contains("t0k");
    }

    // Covers: 8.4, H1 C6b (a stored summary written before fulfillmentOffered existed still parses and reads as not
    // offered; a lab summary is byte-identical to before; an offered view records it and reads back)
    @Test
    void fulfillmentOffered_isRecordedOnlyWhenTrue_andAnOldSummaryReadsFalse() {
        CheckoutView lab = new CheckoutView(new SessionId("s"), CheckoutStatus.READY_FOR_COMPLETE, "USD",
                List.of(new LineView("item_123", 1, 2500, Optional.of("li_1"))), 2500, 200, 2700, Optional.empty(), List.of());
        String plain = CheckoutWire.responseSummary(lab);

        assertThat(plain).doesNotContain("fulfillment");
        assertThat(CheckoutWire.fulfillmentOffered(plain)).isFalse();
        assertThat(CheckoutWire.quoteShape(plain)).isPresent();
        String offered = CheckoutWire.responseSummary(lab.withFulfillmentOffered(true));
        assertThat(CheckoutWire.fulfillmentOffered(offered)).isTrue();
        assertThat(CheckoutWire.quoteShape(offered)).isPresent();
    }
}
