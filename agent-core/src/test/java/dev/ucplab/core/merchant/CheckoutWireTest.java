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

    // Covers: 7.4, A.2 (the frozen complete body carries the instrument; its token can be read back)
    @Test
    void completeBody_carriesInstrument_andTokenReadsBack() {
        FrozenBody body = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"));

        assertThat(body.json()).isEqualTo("{\"payment_data\":{\"credential\":{\"token\":\"tok_ok\",\"type\":\"MOCK\"},"
                + "\"handler_id\":\"mock_pay_1\",\"id\":\"pi_1\",\"type\":\"mock_card\"}}");
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
                + "\"message_codes\":[],\"order_id\":\"ord_1\",\"status\":\"completed\",\"total_minor\":2700}");
    }
}
