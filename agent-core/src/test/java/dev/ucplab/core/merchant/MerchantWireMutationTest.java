package dev.ucplab.core.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.intent.IntentId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the merchant wire helpers that the H3 P5 mutation pass found unchecked (8.4, 7.4, I7, I8): the bytes of
 * the bodies, what is kept in the stored response summary and read back from it, and what a summary that is not ours
 * reads as. A summary is read back to build the next update, so each refusal below keeps a half-read quote from being
 * sent to the merchant.
 */
class MerchantWireMutationTest {

    private static FulfillmentView rich() {
        return new FulfillmentView(List.of(new FulfillmentView.Method("method_1", "shipping", List.of("li_1", "li_2"),
                List.of(new FulfillmentView.Destination("addr_1", Optional.of("US")),
                        new FulfillmentView.Destination("addr_2", Optional.empty())),
                Optional.of("addr_2"),
                List.of(new FulfillmentView.Group("g1", List.of("li_1"),
                                List.of(new FulfillmentView.Option("std", "Standard", 100),
                                        new FulfillmentView.Option("exp", "Express", 50)), Optional.of("exp")),
                        new FulfillmentView.Group("g2", List.of("li_2"),
                                List.of(new FulfillmentView.Option("only", "", 0)), Optional.empty())))));
    }

    private static CheckoutView view(Optional<Buyer> buyer, Optional<FulfillmentView> fulfillment, boolean offered) {
        return new CheckoutView(new SessionId("cs_1"), CheckoutStatus.INCOMPLETE, "USD",
                List.of(new LineView("item_123", 2, 2500, Optional.of("li_1"))), 5000, 400, 5400, Optional.empty(), buyer,
                List.of(), fulfillment, 0, offered);
    }

    private static String summaryWithFulfillment(String fulfillmentJson) {
        return "{\"fulfillment\":" + fulfillmentJson + ",\"lines\":[]}";
    }

    // ---------------------------------------------------------------- bodies

    // Covers: 8.4, I7. Kills CheckoutWire.fulfillmentObject:47 RemoveConditional_EQUAL_IF (a selection with no groups sends
    // no "groups" key; one with a group sends it) and CheckoutWire.lambda$updateBody$0:36 RemoveConditional /
    // BooleanTrueReturnVals (a buyer with no fields sends no "buyer" key)
    @Test
    void updateBody_omitsEmptyGroupsAndAnEmptyBuyer() {
        UpdateLine line = new UpdateLine("item_123", 2, Optional.of("li_1"));
        Buyer none = new Buyer(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        FulfillmentSelection methodOnly = new FulfillmentSelection("method_1", "shipping", List.of("li_1"), Optional.empty(), List.of());
        FulfillmentSelection withGroup = new FulfillmentSelection("method_1", "shipping", List.of("li_1"), Optional.of("addr_1"),
                List.of(new FulfillmentSelection.GroupSelection("g1", List.of("li_1"), "std")));

        String first = CheckoutWire.updateBody(new UpdateCheckout(List.of(line), Optional.of(none), Optional.of(methodOnly)));
        String second = CheckoutWire.updateBody(new UpdateCheckout(List.of(line), Optional.of(Buyer.ofEmail("a@example.com")),
                Optional.of(withGroup)));

        assertThat(first).doesNotContain("\"groups\"").doesNotContain("\"buyer\"").contains("\"fulfillment\"");
        assertThat(second).contains("\"buyer\":{\"email\":\"a@example.com\"}").contains(
                "\"groups\":[{\"id\":\"g1\",\"line_item_ids\":[\"li_1\"],\"selected_option_id\":\"std\"}]")
                .contains("\"selected_destination_id\":\"addr_1\"");
    }

    // Covers: 8.4, I7. Kills CheckoutWire.lambda$responseSummary$8:113 (the stored summary of a view whose buyer has no
    // fields has no "buyer" key; one with an email has it)
    @Test
    void responseSummary_omitsABuyerWithNoFields() {
        Buyer none = new Buyer(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(CheckoutWire.responseSummary(view(Optional.of(none), Optional.empty(), false))).doesNotContain("\"buyer\"");
        assertThat(CheckoutWire.responseSummary(view(Optional.of(Buyer.ofEmail("a@example.com")), Optional.empty(), false)))
                .contains("\"buyer\":{\"email\":\"a@example.com\"}");
    }

    // ---------------------------------------------------------------- the credential in a body

    // Covers: I8, 8.4. Kills CheckoutWire.paymentToken:79 to :83 RemoveConditional_EQUAL_IF (each shape check): a body
    // that is not a complete body of ours has no token, whatever is missing, and never throws; a real one gives its token
    @Test
    void paymentToken_isEmptyForEveryShapeThatIsNotACompleteBody() {
        List<String> notBodies = List.of(
                "{}", "{\"payment\":\"x\"}", "{\"payment\":{}}", "{\"payment\":{\"instruments\":\"x\"}}",
                "{\"payment\":{\"instruments\":[]}}", "{\"payment\":{\"instruments\":[\"x\"]}}",
                "{\"payment\":{\"instruments\":[{}]}}", "{\"payment\":{\"instruments\":[{\"credential\":\"x\"}]}}",
                "{\"payment\":{\"instruments\":[{\"credential\":{}}]}}",
                "{\"payment\":{\"instruments\":[{\"credential\":{\"token\":7}}]}}");
        for (String json : notBodies) {
            assertThat(CheckoutWire.paymentToken(new FrozenBody(json))).as(json).isEmpty();
        }
        assertThat(CheckoutWire.paymentToken(CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"))))
                .contains("tok_ok");
        assertThat(CheckoutWire.paymentToken(new FrozenBody("not json"))).isEmpty();
    }

    // ---------------------------------------------------------------- the summary, written and read back

    // Covers: 8.4, 7.2 shipping selection, I2. Kills CheckoutWire.lambda$fulfillmentSummary$12:134 VoidMethodCall (country
    // kept), strings:214 EmptyObjectReturnVals (line item ids kept) and the reads of fulfillmentShape: what a summary says
    // about the shipping (ids, countries, option labels and prices, selected destination and options) reads back equal
    @Test
    void fulfillmentShape_readsBackWhatResponseSummaryWrote() {
        FulfillmentView written = rich();

        Optional<FulfillmentView> read = CheckoutWire.fulfillmentShape(
                CheckoutWire.responseSummary(view(Optional.empty(), Optional.of(written), true)));

        assertThat(read).contains(written);
    }

    // Covers: 8.4. Kills CheckoutWire.fulfillmentShape:161 RemoveConditional_EQUAL_IF (both checks): a summary with no
    // fulfillment part, or one that is not an object with a list of methods, has no shipping shape
    @Test
    void fulfillmentShape_isEmptyWithoutAFulfillmentObjectWithMethods() {
        for (String json : List.of("{}", "{\"fulfillment\":\"x\"}", "{\"fulfillment\":{}}", "{\"fulfillment\":{\"methods\":\"x\"}}",
                "not json")) {
            assertThat(CheckoutWire.fulfillmentShape(json)).as(json).isEmpty();
        }
        assertThat(CheckoutWire.fulfillmentShape("{\"fulfillment\":{\"methods\":[]}}")).contains(new FulfillmentView(List.of()));
    }

    // Covers: 8.4. Kills CheckoutWire.fulfillmentShape:166 RemoveConditional_EQUAL_IF (all three): a method entry that is
    // not an object, or has no id or no type, makes the whole shape unusable: never a partial one
    @Test
    void fulfillmentShape_isEmptyWhenAMethodIsNotWellFormed() {
        for (String method : List.of("\"x\"", "{\"type\":\"shipping\"}", "{\"id\":\"m\"}", "{\"id\":7,\"type\":\"shipping\"}",
                "{\"id\":\"m\",\"type\":7}")) {
            assertThat(CheckoutWire.fulfillmentShape(summaryWithFulfillment("{\"methods\":[" + method + "]}")))
                    .as(method).isEmpty();
        }
    }

    // Covers: 8.4. Kills CheckoutWire.fulfillmentShape:171, :177, :180, :181 RemoveConditional_EQUAL_IF and :183
    // RemoveConditional_EQUAL_ELSE, list:210: a destination, group or option that is not an object or lacks an id (or an
    // option whose price is not an integer) is left out, the rest is kept; an option with no title has the empty title;
    // a list field that is not a list reads as empty
    @Test
    void fulfillmentShape_skipsEntriesItCannotRead_andKeepsTheRest() {
        String json = summaryWithFulfillment("{\"methods\":[{\"id\":\"m\",\"type\":\"shipping\","
                + "\"destinations\":[\"x\",{\"country\":\"US\"},{\"id\":\"a\",\"country\":\"US\"}],"
                + "\"groups\":[\"x\",{\"options\":[]},{\"id\":\"g\",\"line_item_ids\":[\"li_1\",7],\"options\":[\"x\","
                + "{\"price_minor\":1},{\"id\":\"p\",\"price_minor\":\"5\"},"
                + "{\"id\":\"ok\",\"price_minor\":7}]}]},{\"id\":\"n\",\"type\":\"pickup\",\"destinations\":\"x\",\"groups\":3}]}");

        FulfillmentView read = CheckoutWire.fulfillmentShape(json).orElseThrow();

        assertThat(read.methods()).hasSize(2);
        FulfillmentView.Method m = read.methods().get(0);
        assertThat(m.destinations()).containsExactly(new FulfillmentView.Destination("a", Optional.of("US")));
        assertThat(m.groups()).containsExactly(new FulfillmentView.Group("g", List.of("li_1"),
                List.of(new FulfillmentView.Option("ok", "", 7)), Optional.empty()));
        FulfillmentView.Method n = read.methods().get(1);
        assertThat(n.destinations()).isEmpty();
        assertThat(n.groups()).isEmpty();
        // The canonical reader is strict (8.4): a decimal price makes the whole summary unreadable, never a rounded price.
        assertThat(CheckoutWire.fulfillmentShape(summaryWithFulfillment("{\"methods\":[{\"id\":\"m\",\"type\":\"s\","
                + "\"groups\":[{\"id\":\"g\",\"options\":[{\"id\":\"q\",\"price_minor\":2.5}]}]}]}"))).isEmpty();
    }

    // Covers: 8.4. Kills CheckoutWire.fulfillmentOffered:205 BooleanTrueReturnVals (NO_COVERAGE): a summary that cannot be
    // read says the merchant offered nothing; so does one without the flag or with something other than true
    @Test
    void fulfillmentOffered_isTrueOnlyForTheFlagTrue() {
        assertThat(CheckoutWire.fulfillmentOffered("not json")).isFalse();
        assertThat(CheckoutWire.fulfillmentOffered("{}")).isFalse();
        assertThat(CheckoutWire.fulfillmentOffered("{\"fulfillment_offered\":\"true\"}")).isFalse();
        assertThat(CheckoutWire.fulfillmentOffered("{\"fulfillment_offered\":true}")).isTrue();
    }

    // Covers: 8.4. Kills CheckoutWire.quoteShape:230 and :231 RemoveConditional_EQUAL_IF (all four): a summary whose lines
    // are not all objects with an item id, an integer quantity and an integer price gives no quote shape, so no update is
    // built from half a quote; a good one gives the line, its id and the buyer
    @Test
    void quoteShape_isEmptyUnlessEveryLineIsComplete() {
        for (String line : List.of("\"x\"", "{\"quantity\":1,\"unit_price_minor\":5}",
                "{\"item_id\":\"i\",\"unit_price_minor\":5}", "{\"item_id\":\"i\",\"quantity\":\"1\",\"unit_price_minor\":5}",
                "{\"item_id\":\"i\",\"quantity\":1}", "{\"item_id\":\"i\",\"quantity\":1,\"unit_price_minor\":5.5}")) {
            assertThat(CheckoutWire.quoteShape("{\"lines\":[" + line + "]}")).as(line).isEmpty();
        }
        assertThat(CheckoutWire.quoteShape("{}")).isEmpty();
        QuoteShape shape = CheckoutWire.quoteShape("{\"lines\":[{\"item_id\":\"i\",\"line_id\":\"li_1\",\"quantity\":2,"
                + "\"unit_price_minor\":5}],\"buyer\":{\"email\":\"a@example.com\"}}").orElseThrow();
        assertThat(shape.lines()).containsExactly(new LineView("i", 2, 5, Optional.of("li_1")));
        assertThat(shape.buyer()).contains(Buyer.ofEmail("a@example.com"));
    }

    // ---------------------------------------------------------------- selection steps

    private static FulfillmentView.Group group(String id, List<FulfillmentView.Option> options, Optional<String> selected) {
        return new FulfillmentView.Group(id, List.of("li_" + id), options, selected);
    }

    private static FulfillmentView withMethod(Optional<String> destination, FulfillmentView.Group... groups) {
        return new FulfillmentView(List.of(new FulfillmentView.Method("method_1", "shipping", List.of("li_1"),
                List.of(new FulfillmentView.Destination("addr_1", Optional.of("US"))), destination, List.of(groups))));
    }

    // Covers: 7.2 shipping selection, 8.4. Kills FulfillmentSteps.next:30 RemoveConditional_EQUAL_ELSE and carry:61
    // RemoveConditional_EQUAL_ELSE: a response whose fulfillment part lists no method at all asks for the method first
    // (and there is nothing to carry)
    @Test
    void steps_aFulfillmentPartWithNoMethods_asksForTheMethod_andCarriesNothing() {
        Optional<FulfillmentView> none = Optional.of(new FulfillmentView(List.of()));

        assertThat(FulfillmentSteps.next(none, List.of("li_1"), FulfillmentPolicy.cheapest())).contains(
                new FulfillmentSelection("method_1", "shipping", List.of("li_1"), Optional.empty(), List.of()));
        assertThat(FulfillmentSteps.carry(none)).isEmpty();
    }

    // Covers: 7.2 shipping selection, 8.4. Kills FulfillmentSteps.carry:65 RemoveConditional_EQUAL_ELSE and
    // lambda$carry$2:69 BooleanTrueReturnVals: a re-quote repeats only what the merchant had selected: nothing before a
    // destination is selected, and only the groups whose option is selected after
    @Test
    void carry_repeatsOnlyWhatIsSelected() {
        FulfillmentView.Option std = new FulfillmentView.Option("std", "Standard", 0);
        FulfillmentView noDestination = withMethod(Optional.empty());
        FulfillmentView partly = withMethod(Optional.of("addr_1"), group("1", List.of(std), Optional.of("std")),
                group("2", List.of(std), Optional.empty()));

        assertThat(FulfillmentSteps.carry(Optional.of(noDestination))).isEmpty();
        assertThat(FulfillmentSteps.carry(Optional.of(partly)).orElseThrow().groups())
                .containsExactly(new FulfillmentSelection.GroupSelection("1", List.of("li_1"), "std"));
    }

    // Covers: 7.2 shipping selection, 8.4. Kills FulfillmentSteps.next:45 RemoveConditional_EQUAL_ELSE and next:46
    // RemoveConditional_EQUAL_ELSE: a group that already has its option keeps it while another group is chosen for; a
    // group with no option to offer ends the selection with nothing to send
    @Test
    void next_keepsSelectedOptions_andStopsWhenAGroupHasNoOption() {
        FulfillmentView.Option std = new FulfillmentView.Option("std", "Standard", 0);
        FulfillmentView.Option exp = new FulfillmentView.Option("exp", "Express", 1500);
        FulfillmentView twoGroups = withMethod(Optional.of("addr_1"), group("1", List.of(std, exp), Optional.of("exp")),
                group("2", List.of(std, exp), Optional.empty()));
        FulfillmentView noOptions = withMethod(Optional.of("addr_1"), group("1", List.of(), Optional.empty()));

        assertThat(FulfillmentSteps.next(Optional.of(twoGroups), List.of("li_1"), FulfillmentPolicy.cheapest()).orElseThrow()
                .groups()).containsExactly(new FulfillmentSelection.GroupSelection("1", List.of("li_1"), "exp"),
                new FulfillmentSelection.GroupSelection("2", List.of("li_2"), "std"));
        assertThat(FulfillmentSteps.next(Optional.of(noOptions), List.of("li_1"), FulfillmentPolicy.cheapest())).isEmpty();
    }

    // Covers: 7.2 shipping selection. Kills FulfillmentPolicy$1.option:39 ConditionalsBoundary and
    // RemoveConditional_ORDER_ELSE: the cheapest option is chosen; of equal prices, the first
    @Test
    void cheapest_choosesTheLowestPrice_andTheFirstOfEqualPrices() {
        FulfillmentPolicy policy = FulfillmentPolicy.cheapest();
        FulfillmentView.Option a100 = new FulfillmentView.Option("a", "A", 100);
        FulfillmentView.Option b100 = new FulfillmentView.Option("b", "B", 100);
        FulfillmentView.Option c50 = new FulfillmentView.Option("c", "C", 50);

        assertThat(policy.option(List.of(a100, b100))).contains("a");
        assertThat(policy.option(List.of(a100, c50))).contains("c");
        assertThat(policy.option(List.of(c50, a100))).contains("c");
        assertThat(policy.option(List.of())).isEmpty();
    }

    // ---------------------------------------------------------------- small guards

    // Covers: 7.4, I7. Kills IdempotencyKey.<init>:21 RemoveConditional_EQUAL_ELSE: a blank key is refused
    @Test
    void idempotencyKey_refusesABlankValue() {
        assertThatThrownBy(() -> new IdempotencyKey(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(IdempotencyKey.derive(new IntentId("i-1"), MutatingOp.CREATE, 0).value()).isNotBlank();
    }

    // Covers: 7.3, I8. Kills FrozenBody.<init>:15 RemoveConditional_EQUAL_ELSE (a blank body is refused) and
    // FrozenBody.toString:27 EmptyObjectReturnVals (the string form shows only the size, which is what a log line says,
    // and never the content)
    @Test
    void frozenBody_refusesABlankBody_andItsStringFormShowsOnlyTheSize() {
        assertThatThrownBy(() -> new FrozenBody(" ")).isInstanceOf(IllegalArgumentException.class);

        FrozenBody body = new FrozenBody("{\"token\":\"tok_secret\"}");

        assertThat(body.toString()).isEqualTo("FrozenBody[22 bytes]").doesNotContain("tok_secret");
    }

    // Covers: 8.5, 19. Kills MerchantInstruments.lambda$of$0:35 RemoveConditional_EQUAL_ELSE: a merchant with no instrument
    // is refused with NoInstrumentException, never a null instrument
    @Test
    void instrumentsByMerchant_refuseAMerchantWithNone() {
        PaymentInstrument card = PaymentInstrument.mockCard("pi_1", "tok_ok");
        var byMerchant = MerchantInstruments.of(Map.of(new MerchantId("shop-1"), card));

        assertThat(byMerchant.apply(new MerchantId("shop-1"))).isSameAs(card);
        assertThatThrownBy(() -> byMerchant.apply(new MerchantId("other"))).isInstanceOf(NoInstrumentException.class);
    }
}
