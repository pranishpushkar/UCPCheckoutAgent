package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.merchant.Buyer;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.FulfillmentView;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.OrderRef;
import dev.ucplab.core.merchant.SessionId;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RedactorFieldsTest {

    // Covers: I8, 8.4 (H1 C8a: scrubbing a view changes only the scrubbed text. Every record component of CheckoutView is
    // set to a non-default value and, by reflection, compared after the scrub, so a field added later cannot be dropped
    // silently as the fulfillment part once was)
    @Test
    void scrubbing_keepsEveryComponentOfTheView() throws Exception {
        CheckoutView full = new CheckoutView(new SessionId("s-1"), CheckoutStatus.READY_FOR_COMPLETE, "USD",
                List.of(new LineView("item_1", 2, 1250, Optional.of("li_1"))), 2500, 200, 4200, Optional.of(new OrderRef("o-1")),
                Optional.of(Buyer.ofEmail("a@example.com")), List.of(new MerchantMessage("warning", "w", Optional.of("$.x"), "text", Optional.empty())),
                Optional.of(new FulfillmentView(List.of(new FulfillmentView.Method("m", "shipping", List.of("li_1"),
                        List.of(new FulfillmentView.Destination("addr_1", Optional.of("US"))), Optional.of("addr_1"),
                        List.of(new FulfillmentView.Group("g", List.of("li_1"),
                                List.of(new FulfillmentView.Option("std", "Standard", 1500)), Optional.of("std"))))))),
                1500, true);
        for (RecordComponent c : CheckoutView.class.getRecordComponents()) {
            Object v = c.getAccessor().invoke(full);
            assertThat(v).as("the test sets %s to a non-default value", c.getName()).isNotNull();
            assertThat(v).as(c.getName()).isNotEqualTo(Optional.empty()).isNotEqualTo(List.of()).isNotEqualTo(0L).isNotEqualTo(false);
        }

        CallOutcome<CheckoutView> out = new Redactor(List.of("tok_secret")).scrub(new CallOutcome.Ok<>(200, full, full.messages()));

        CheckoutView clean = ((CallOutcome.Ok<CheckoutView>) out).value();
        for (RecordComponent c : CheckoutView.class.getRecordComponents()) {
            assertThat(c.getAccessor().invoke(clean)).as("component %s survives the scrub", c.getName())
                    .isEqualTo(c.getAccessor().invoke(full));
        }
    }
}
