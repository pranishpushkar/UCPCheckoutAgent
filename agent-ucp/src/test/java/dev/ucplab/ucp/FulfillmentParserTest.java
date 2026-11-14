package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.FulfillmentView;
import java.nio.charset.StandardCharsets;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fulfillment part of a quote is read (2.1, 8.1; H1 C5) from the sample merchant's wire records, fixtures 04 to 07,
 * and an address never leaves the parser: only ids, the country, the options and their prices.
 */
class FulfillmentParserTest {

    private static final String CANARY_STREET = "CANARY-STREET-9";
    private static final String CANARY_POSTAL = "CANARY-ZIP-77";

    private static CheckoutView view(String fixture) {
        CheckoutParser.Parsed parsed = CheckoutParser.parseCheckout(InteropFixtures.reply(fixture).body());
        assertThat(parsed).as(fixture).isInstanceOf(CheckoutParser.Parsed.Checkout.class);
        return ((CheckoutParser.Parsed.Checkout) parsed).view();
    }

    // Covers: 2.1, 8.1 (fixture 04: the incomplete create has no fulfillment part, and says so)
    @Test
    void theIncompleteCreate_hasNoFulfillmentPart() {
        CheckoutView v = view("04-happy-create");

        assertThat(v.status()).isEqualTo(CheckoutStatus.INCOMPLETE);
        assertThat(v.fulfillment()).isEmpty();
        assertThat(v.fulfillmentMinor()).isZero();
        assertThat(v.totalMinor()).isEqualTo(3500);
    }

    // Covers: 2.1, 8.1 (fixture 05: the method and the destinations the merchant offers, by id and country; nothing selected)
    @Test
    void theMethodsStep_readsTheMethodAndItsDestinations() {
        CheckoutView v = view("05-happy-fulfillment-1-methods");

        assertThat(v.status()).isEqualTo(CheckoutStatus.INCOMPLETE);
        FulfillmentView.Method m = v.fulfillment().orElseThrow().methods().getFirst();
        assertThat(m.id()).isEqualTo("method_1");
        assertThat(m.type()).isEqualTo("shipping");
        assertThat(m.lineItemIds()).hasSize(1);
        assertThat(m.destinations()).extracting(FulfillmentView.Destination::id).containsExactly("addr_1", "addr_2");
        assertThat(m.destinations()).extracting(d -> d.country().orElseThrow()).containsOnly("US");
        assertThat(m.selectedDestinationId()).isEmpty();
        assertThat(m.groups()).isEmpty();
    }

    // Covers: 2.1, 8.1 (fixture 06: with a destination selected the merchant offers the groups and their options, with
    // their prices; the group id is the one the next update must repeat)
    @Test
    void theDestinationStep_readsTheGroupsAndOptionsWithPrices() {
        CheckoutView v = view("06-happy-fulfillment-2-destination");

        FulfillmentView.Method m = v.fulfillment().orElseThrow().methods().getFirst();
        assertThat(m.selectedDestinationId()).contains("addr_1");
        FulfillmentView.Group g = m.groups().getFirst();
        assertThat(g.id()).startsWith("group_");
        assertThat(g.lineItemIds()).isEqualTo(m.lineItemIds());
        assertThat(g.options()).extracting(FulfillmentView.Option::id, FulfillmentView.Option::priceMinor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("std-ship", 0L),
                        org.assertj.core.groups.Tuple.tuple("exp-ship-us", 1500L));
        assertThat(g.options().getFirst().title()).isEqualTo("Standard Shipping (Free)");
        assertThat(g.selectedOptionId()).isEmpty();
        assertThat(v.status()).isEqualTo(CheckoutStatus.INCOMPLETE);
    }

    // Covers: 2.1, 8.1 (fixture 07: the option selected, ready_for_complete, and the fulfillment total line is read;
    // the total includes it)
    @Test
    void theOptionStep_isReady_withTheSelectionAndTheFulfillmentTotalLine() {
        CheckoutView v = view("07-happy-fulfillment-3-option");

        assertThat(v.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
        FulfillmentView.Group g = v.fulfillment().orElseThrow().methods().getFirst().groups().getFirst();
        assertThat(g.selectedOptionId()).contains("std-ship");
        assertThat(v.fulfillmentMinor()).isZero();
        assertThat(v.totalMinor()).isEqualTo(3500);
    }

    // Covers: 2.1 (a priced fulfillment total line is read: express shipping adds 1500 to a 3500 subtotal)
    @Test
    void aPricedFulfillmentTotal_isRead() {
        String body = new String(InteropFixtures.reply("07-happy-fulfillment-3-option").body(), StandardCharsets.UTF_8)
                .replace("\"selected_option_id\":\"std-ship\"", "\"selected_option_id\":\"exp-ship-us\"")
                .replace("{\"amount\":0,\"type\":\"fulfillment\"}", "{\"amount\":1500,\"type\":\"fulfillment\"}")
                .replace("\"amount\":3500,\"type\":\"total\"", "\"amount\":5000,\"type\":\"total\"");

        CheckoutParser.Parsed parsed = CheckoutParser.parseCheckout(body.getBytes(StandardCharsets.UTF_8));

        CheckoutView v = ((CheckoutParser.Parsed.Checkout) parsed).view();
        assertThat(v.fulfillmentMinor()).isEqualTo(1500);
        assertThat(v.totalMinor()).isEqualTo(5000);
        assertThat(v.fulfillment().orElseThrow().methods().getFirst().groups().getFirst().selectedOptionId())
                .contains("exp-ship-us");
    }

    // Covers: 8.1, I8-style hygiene, D4 (an address never leaves the parser: the street and postal code of the
    // destinations are in no toString, no stored response summary and no log line; the country and ids are)
    @Test
    void anAddress_isNeverKept() {
        String body = new String(InteropFixtures.reply("06-happy-fulfillment-2-destination").body(), StandardCharsets.UTF_8)
                .replace("123 Main St", CANARY_STREET).replace("62704", CANARY_POSTAL);
        assertThat(body).contains(CANARY_STREET);
        List<String> logs = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logs.add(r.getMessage() + " " + java.util.Arrays.toString(r.getParameters()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(capture);
        try {
            CheckoutView v = ((CheckoutParser.Parsed.Checkout) CheckoutParser.parseCheckout(
                    body.getBytes(StandardCharsets.UTF_8))).view();
            CallOutcome<CheckoutView> outcome = new FailureClassifier(new TestClock()).classify(AttemptOp.UPDATE,
                    new HttpReply(200, java.util.Map.of(), body.getBytes(StandardCharsets.UTF_8)));

            assertThat(v.toString()).doesNotContain(CANARY_STREET, CANARY_POSTAL, "Springfield", "Main St");
            assertThat(v.fulfillment().toString()).doesNotContain(CANARY_STREET, CANARY_POSTAL, "Springfield");
            assertThat(outcome.toString()).doesNotContain(CANARY_STREET, CANARY_POSTAL);
            assertThat(CheckoutWire.responseSummary(v)).doesNotContain(CANARY_STREET, CANARY_POSTAL);
            assertThat(v.fulfillment().orElseThrow().methods().getFirst().destinations().getFirst().country()).contains("US");
        } finally {
            root.removeHandler(capture);
        }
        assertThat(logs).noneMatch(l -> l.contains(CANARY_STREET) || l.contains(CANARY_POSTAL));
    }

    // Covers: 8.1, D6 (requires_escalation is a status we parse: a mutating call that returns it is an Ok with that
    // status, not Uncertain; a read of it is an Ok, not a ProtocolError)
    @Test
    void aBodyWithRequiresEscalation_isOk_notUncertain() {
        String body = new String(InteropFixtures.reply("05-happy-fulfillment-1-methods").body(), StandardCharsets.UTF_8)
                .replace("\"status\":\"incomplete\"", "\"status\":\"requires_escalation\"");
        HttpReply reply = new HttpReply(200, java.util.Map.of(), body.getBytes(StandardCharsets.UTF_8));
        FailureClassifier c = new FailureClassifier(new TestClock());

        for (AttemptOp op : List.of(AttemptOp.CREATE, AttemptOp.UPDATE, AttemptOp.COMPLETE, AttemptOp.GET)) {
            assertThat(c.classify(op, reply)).as(op.name()).isInstanceOfSatisfying(CallOutcome.Ok.class,
                    ok -> assertThat(((CheckoutView) ok.value()).status()).isEqualTo(CheckoutStatus.REQUIRES_ESCALATION));
        }
        assertThat(CheckoutStatus.parse("requires_escalation")).isEqualTo(CheckoutStatus.REQUIRES_ESCALATION);
    }
}
