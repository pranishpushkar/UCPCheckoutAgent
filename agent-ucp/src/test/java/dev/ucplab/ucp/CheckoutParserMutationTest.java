package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.merchant.Buyer;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.ContinueUrl;
import dev.ucplab.core.merchant.FulfillmentView;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.OrderRef;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * H3 P8: what the parser takes from a merchant's checkout (8.1, 8.4, 8.5, D4), field by field. Each test names the mutants it
 * kills by method and line.
 */
class CheckoutParserMutationTest {

    private static final String LINE = "{\"id\":\"li_1\",\"item\":{\"id\":\"item_1\",\"price\":1250},\"quantity\":2}";
    private static final String TOTALS = "[{\"type\":\"subtotal\",\"amount\":2500},{\"type\":\"total\",\"amount\":2500}]";

    private static String checkout(String lines, String totals, String extras) {
        return "{\"id\":\"chk_1\",\"status\":\"ready_for_complete\",\"currency\":\"USD\",\"line_items\":" + lines + ",\"totals\":"
                + totals + (extras.isEmpty() ? "" : "," + extras) + "}";
    }

    private static String checkout(String extras) {
        return checkout("[" + LINE + "]", TOTALS, extras);
    }

    private static CheckoutParser.Parsed parse(String json) {
        return CheckoutParser.parseCheckout(json.getBytes(StandardCharsets.UTF_8));
    }

    private static CheckoutView view(String json) {
        CheckoutParser.Parsed p = parse(json);
        assertThat(p).as(json).isInstanceOf(CheckoutParser.Parsed.Checkout.class);
        return ((CheckoutParser.Parsed.Checkout) p).view();
    }

    private static void invalid(String json, String reason) {
        assertThat(parse(json)).as(json).isEqualTo(new CheckoutParser.Parsed.Invalid(reason));
    }

    // ---------------------------------------------------------------- the envelope, the id and the body itself

    // Covers: 8.1. Kills CheckoutParser.parseCheckout:84 (the reason a body is refused: a named field, or "unparseable body")
    @Test
    void aBodyThatCannotBeRead_isUnparseable_anIncompleteOneNamesTheField() {
        invalid("not json", "unparseable body");
        assertThat(CheckoutParser.parseCheckout(new byte[] {'{', (byte) 0xC3, (byte) 0x28, '}'}))
                .isEqualTo(new CheckoutParser.Parsed.Invalid("unparseable body"));
        invalid("{\"id\":\"chk_1\"}", "status missing");
        invalid("{\"currency\":\"USD\"}", "id missing");
    }

    // Covers: 8.1. Kills CheckoutParser.rejectedEnvelope:126 RemoveConditional (an error envelope with no message is not a
    // rejection, an envelope with messages is), and parseCheckout's `containsKey("id")` branch
    @Test
    void anErrorEnvelope_isARejection_onlyWithItsMessages() {
        invalid("{\"ucp\":{\"status\":\"error\"},\"messages\":[]}", "error envelope without messages");
        invalid("{\"ucp\":{\"status\":\"error\"}}", "error envelope without messages");
        invalid("{\"ucp\":{\"status\":\"ok\"},\"messages\":[{\"type\":\"error\",\"code\":\"c\",\"content\":\"x\"}]}", "id missing");
        assertThat(parse("{\"ucp\":{\"status\":\"error\"},\"messages\":[{\"type\":\"error\",\"code\":\"out_of_stock\",\"content\":\"gone\"}]}"))
                .isEqualTo(new CheckoutParser.Parsed.CreateRejected(
                        List.of(new MerchantMessage("error", "out_of_stock", Optional.empty(), "gone", Optional.empty()))));
    }

    // Covers: 8.1. Kills CheckoutParser.text:346 (a text field is a non-blank string)
    @Test
    void aTextFieldThatIsBlankOrNotAString_isMissing() {
        invalid("{\"id\":\" \",\"status\":\"ready_for_complete\"}", "id missing");
        invalid("{\"id\":5,\"status\":\"ready_for_complete\"}", "id missing");
        invalid(checkout("").replace("\"currency\":\"USD\"", "\"currency\":\"\""), "currency missing");
        invalid(checkout("").replace("\"ready_for_complete\"", "\"nope\""), "unknown status");
    }

    // ---------------------------------------------------------------- lines and totals

    // Covers: 8.1. Kills CheckoutParser.lines:159, :163, :167 and minor:372 (the shape of a line, the quantity range, the id of a
    // line, a price that is not negative)
    @Test
    void aLine_needsItsItemAndAQuantityAndAPrice() {
        invalid(checkout("{}", TOTALS, ""), "line_items missing");
        invalid(checkout("\"x\"", TOTALS, ""), "line_items missing");
        invalid(checkout("[\"x\"]", TOTALS, ""), "line item shape");
        invalid(checkout("[{\"quantity\":1}]", TOTALS, ""), "line item shape");
        invalid(checkout("[{\"item\":\"x\",\"quantity\":1}]", TOTALS, ""), "line item shape");
        invalid(checkout("[{\"item\":{\"id\":\"i\",\"price\":1},\"quantity\":0}]", TOTALS, ""), "quantity out of range");
        invalid(checkout("[{\"item\":{\"id\":\"i\",\"price\":1},\"quantity\":-3}]", TOTALS, ""), "quantity out of range");
        invalid(checkout("[{\"item\":{\"id\":\"i\",\"price\":1},\"quantity\":2147483648}]", TOTALS, ""), "quantity out of range");
        invalid(checkout("[{\"item\":{\"id\":\"i\",\"price\":1},\"quantity\":\"2\"}]", TOTALS, ""), "quantity not an integer");
        invalid(checkout("[{\"item\":{\"id\":\"i\",\"price\":1},\"quantity\":1.5}]", TOTALS, ""), "quantity not an integer");
        invalid(checkout("[{\"item\":{\"id\":\"i\",\"price\":-1},\"quantity\":1}]", TOTALS, ""), "item.price negative");
        invalid(checkout("[{\"item\":{\"id\":\"i\",\"price\":\"5\"},\"quantity\":1}]", TOTALS, ""), "item.price not an integer");
        invalid(checkout("[{\"item\":{\"price\":1},\"quantity\":1}]", TOTALS, ""), "id missing");
    }

    // Covers: 8.1. Kills CheckoutParser.lines:163, :167, minor:372 (the edges that are accepted: a quantity of 1 and of
    // Integer.MAX_VALUE, a free item, a blank or absent line id, an empty list)
    @Test
    void theEdgesOfALine_areAccepted() {
        CheckoutView v = view(checkout("[{\"id\":\"li_9\",\"item\":{\"id\":\"a\",\"price\":0},\"quantity\":1},"
                + "{\"id\":\" \",\"item\":{\"id\":\"b\",\"price\":7},\"quantity\":2147483647},"
                + "{\"id\":5,\"item\":{\"id\":\"c\",\"price\":7},\"quantity\":3},"
                + "{\"item\":{\"id\":\"d\",\"price\":7},\"quantity\":4}]", TOTALS, ""));

        assertThat(v.lines()).containsExactly(new LineView("a", 1, 0, Optional.of("li_9")),
                new LineView("b", Integer.MAX_VALUE, 7, Optional.empty()), new LineView("c", 3, 7, Optional.empty()),
                new LineView("d", 4, 7, Optional.empty()));
        assertThat(view(checkout("[]", TOTALS, "")).lines()).isEmpty();
    }

    // Covers: 8.1. Kills CheckoutParser.totals:183 and once (the totals: a list of typed amounts, each once, none negative, a
    // total required, the others 0 when missing, unknown types ignored)
    @Test
    void theTotals_areReadOncePerType() {
        CheckoutView full = view(checkout("[" + LINE + "]",
                "[{\"type\":\"subtotal\",\"amount\":100},{\"type\":\"tax\",\"amount\":10},{\"type\":\"fulfillment\",\"amount\":5},"
                        + "{\"type\":\"discount\",\"amount\":-9},{\"type\":\"total\",\"amount\":115}]", ""));
        assertThat(full.subtotalMinor()).isEqualTo(100);
        assertThat(full.taxMinor()).isEqualTo(10);
        assertThat(full.fulfillmentMinor()).isEqualTo(5);
        assertThat(full.totalMinor()).isEqualTo(115);

        CheckoutView onlyTotal = view(checkout("[" + LINE + "]", "[{\"type\":\"total\",\"amount\":0}]", ""));
        assertThat(onlyTotal.totalMinor()).isZero();
        assertThat(onlyTotal.subtotalMinor()).isZero();
        assertThat(onlyTotal.taxMinor()).isZero();
        assertThat(onlyTotal.fulfillmentMinor()).isZero();

        invalid(checkout("[" + LINE + "]", "\"x\"", ""), "totals missing");
        invalid(checkout("[" + LINE + "]", "[\"x\"]", ""), "totals entry shape");
        invalid(checkout("[" + LINE + "]", "[{\"type\":\"subtotal\",\"amount\":1}]", ""), "total missing");
        invalid(checkout("[" + LINE + "]", "[{\"type\":\"total\",\"amount\":1},{\"type\":\"total\",\"amount\":1}]", ""), "duplicate total");
        invalid(checkout("[" + LINE + "]", "[{\"type\":\"tax\",\"amount\":1},{\"type\":\"tax\",\"amount\":2},{\"type\":\"total\",\"amount\":1}]", ""),
                "duplicate tax");
        invalid(checkout("[" + LINE + "]", "[{\"type\":\"total\",\"amount\":-1}]", ""), "negative total");
        invalid(checkout("[" + LINE + "]", "[{\"type\":\"total\",\"amount\":\"1\"}]", ""), "totals.amount not an integer");
        invalid(checkout("[" + LINE + "]", "[{\"amount\":1}]", ""), "type missing");
    }

    // ---------------------------------------------------------------- order, buyer, messages

    // Covers: 8.1. Kills CheckoutParser.order (absent, wrong shape, without an id)
    @Test
    void theOrder_isAbsent_orAnObjectWithAnId() {
        assertThat(view(checkout("")).order()).isEmpty();
        assertThat(view(checkout("\"order\":{\"id\":\"ord_1\"}")).order()).contains(new OrderRef("ord_1"));
        invalid(checkout("\"order\":\"ord_1\""), "order shape");
        invalid(checkout("\"order\":{}"), "id missing");
    }

    // Covers: VERIFY 7, VERIFY 11, 7.6, I8 (a completed checkout as the store answered after payment, every value replaced:
    // the order's id and its label, the confirmation code, are read; its permalink_url, whose session_token opens the
    // buyer's order page, is never read; a label that is not a non-blank string is left out, not refused)
    @Test
    void aCompletedShopifyCheckout_readsTheOrderIdAndLabel_andIgnoresThePermalink() {
        String paid = "{\"id\":\"gid://shopify/Checkout/tok?key=k\",\"status\":\"completed\",\"currency\":\"USD\","
                + "\"line_items\":[" + LINE + "],\"totals\":" + TOTALS + ",\"order\":{\"id\":\"gid://shopify/Order/42\","
                + "\"label\":\"CANARYLABEL7\",\"permalink_url\":\"https://shop.example/checkouts/cn/t/thank_you"
                + "?session_token=canary-session-666\"}}";
        CheckoutView view = view(paid);

        assertThat(view.status()).isEqualTo(dev.ucplab.core.merchant.CheckoutStatus.COMPLETED);
        assertThat(view.order()).contains(new OrderRef("gid://shopify/Order/42", Optional.of("CANARYLABEL7")));
        assertThat(view.toString()).doesNotContain("canary-session-666");
        assertThat(view(checkout("\"order\":{\"id\":\"o\",\"label\":5}")).order()).contains(new OrderRef("o"));
        assertThat(view(checkout("\"order\":{\"id\":\"o\",\"label\":\" \"}")).order()).contains(new OrderRef("o"));
    }

    private static String titledLine(String title) {
        return "[{\"id\":\"li_1\",\"item\":{\"id\":\"item_1\",\"price\":1250,\"title\":" + title + "},\"quantity\":2}]";
    }

    // Covers: 9.2, 8.1 (a line's title is the merchant's item.title, read leniently: control characters become spaces,
    // it is trimmed and cut to 100 characters, and a missing or non-string title is empty, never a refusal)
    @Test
    void aLinesTitle_isTheItemsTitle_cleaned_andEmptyWhenAbsentOrNotAString() {
        assertThat(view(checkout(titledLine("\"The Complete Snowboard - Ice\""), TOTALS, "")).lines().get(0).title())
                .isEqualTo("The Complete Snowboard - Ice");
        assertThat(view(checkout(titledLine("\"  Board\\u0007Ice\\n \""), TOTALS, "")).lines().get(0).title())
                .isEqualTo("Board Ice");
        String longTitle = "x".repeat(150);
        assertThat(view(checkout(titledLine("\"" + longTitle + "\""), TOTALS, "")).lines().get(0).title())
                .isEqualTo("x".repeat(100));
        assertThat(view(checkout(titledLine("5"), TOTALS, "")).lines().get(0).title()).isEmpty();
        assertThat(view(checkout(titledLine("null"), TOTALS, "")).lines().get(0).title()).isEmpty();
        assertThat(view(checkout("")).lines().get(0).title()).as("no title").isEmpty();
    }

    // Covers: 8.1. Kills CheckoutParser.lenientText:308 (the buyer: every field optional, a field of another type is left out)
    @Test
    void theBuyer_isLeniant_aboutEveryField() {
        assertThat(view(checkout("")).buyer()).isEmpty();
        assertThat(view(checkout("\"buyer\":\"x\"")).buyer()).isEmpty();
        assertThat(view(checkout("\"buyer\":{\"email\":\"a@example.com\",\"first_name\":\" \",\"last_name\":5,\"phone_number\":\"+1\"}")).buyer())
                .contains(new Buyer(Optional.of("a@example.com"), Optional.empty(), Optional.empty(), Optional.of("+1")));
        assertThat(view(checkout("\"buyer\":{}")).buyer())
                .contains(new Buyer(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    // Covers: 8.1. Kills CheckoutParser.messages:325, :330, :338 and optionalText:357 (message types, codes, content, optional
    // path and severity, and the shapes that are refused)
    @Test
    void theMessages_areTypedAndCoded() {
        CheckoutView v = view(checkout("\"messages\":[{\"type\":\"error\",\"code\":\"e1\",\"content\":\"c1\",\"path\":\"$.x\",\"severity\":\"recoverable\"},"
                + "{\"type\":\"warning\",\"code\":\"w1\",\"content\":\"c2\"},{\"type\":\"info\",\"content\":\"c3\"},"
                + "{\"type\":\"info\",\"code\":\"i1\",\"content\":\"c4\"}]"));
        assertThat(v.messages()).containsExactly(
                new MerchantMessage("error", "e1", Optional.of("$.x"), "c1", Optional.of("recoverable")),
                new MerchantMessage("warning", "w1", Optional.empty(), "c2", Optional.empty()),
                new MerchantMessage("info", "", Optional.empty(), "c3", Optional.empty()),
                new MerchantMessage("info", "i1", Optional.empty(), "c4", Optional.empty()));
        assertThat(view(checkout("\"messages\":[]")).messages()).isEmpty();
        assertThat(view(checkout("")).messages()).isEmpty();

        invalid(checkout("\"messages\":\"x\""), "messages shape");
        invalid(checkout("\"messages\":[\"x\"]"), "message shape");
        invalid(checkout("\"messages\":[{\"type\":\"debug\",\"code\":\"c\",\"content\":\"x\"}]"), "unknown message type");
        invalid(checkout("\"messages\":[{\"type\":\"error\",\"content\":\"x\"}]"), "code missing");
        invalid(checkout("\"messages\":[{\"type\":\"warning\",\"content\":\"x\"}]"), "code missing");
        invalid(checkout("\"messages\":[{\"type\":\"info\",\"code\":\" \",\"content\":\"x\"}]"), "code missing");
        invalid(checkout("\"messages\":[{\"type\":\"error\",\"code\":\"c\"}]"), "content missing");
        invalid(checkout("\"messages\":[{\"type\":\"error\",\"code\":\"c\",\"content\":\"x\",\"path\":5}]"), "path shape");
        invalid(checkout("\"messages\":[{\"type\":\"error\",\"code\":\"c\",\"content\":\"x\",\"severity\":{}}]"), "severity shape");
    }

    // ---------------------------------------------------------------- fulfillment (D4)

    private static FulfillmentView fulfillment(String json) {
        Optional<FulfillmentView> f = view(checkout("\"fulfillment\":" + json)).fulfillment();
        assertThat(f).as(json).isPresent();
        return f.get();
    }

    // Covers: 8.1, D4. Kills CheckoutParser.fulfillment:209, :214, :215 and destinations/groups/options/strings/optionalId (the
    // fulfillment part: what is kept, what is left out, ids cut to 64 characters, the country upper-cased to two, titles to
    // 100; an address is never read beyond its id and country)
    @Test
    void theFulfillmentPart_keepsWhatItIsGiven_andLeavesTheRestOut() {
        String longId = "x".repeat(80);
        FulfillmentView f = fulfillment("{\"methods\":["
                + "{\"id\":\"m1\",\"type\":\"shipping\",\"line_item_ids\":[\"li_1\",\" \",5,\"li_2\"],"
                + "\"destinations\":[{\"id\":\"d1\",\"address_country\":\"us\",\"street_address\":\"1 Main St\"},{\"id\":\" \",\"address_country\":\"US\"},"
                + "{\"id\":\"d2\"},\"str\",{\"id\":\"d3\",\"address_country\":\" \"},{\"id\":\"d4\",\"address_country\":\"gbr\"},{\"id\":5}],"
                + "\"selected_destination_id\":\"d1\","
                + "\"groups\":[{\"line_item_ids\":[\"nogroup\"]},{\"id\":7},{\"id\":\"g1\",\"line_item_ids\":[\"li_1\"],\"options\":["
                + "{\"id\":\"o1\",\"title\":\"Standard\",\"totals\":[{\"type\":\"total\",\"amount\":500},{\"type\":\"tax\",\"amount\":9},{\"type\":\"total\",\"amount\":-1}]},{\"title\":\"no id\"},{\"id\":8,\"title\":\"numeric id\"},"
                + "{\"id\":\" \",\"title\":\"blank\"},{\"id\":\"o2\"},{\"id\":\"o3\",\"title\":5,\"totals\":\"x\"},"
                + "{\"id\":\"o4\",\"title\":\"Fast\",\"totals\":[{\"type\":\"total\",\"amount\":300},{\"type\":\"total\",\"amount\":0},\"x\",{\"type\":\"total\",\"amount\":\"7\"}]},"
                + "{\"id\":\"" + longId + "\",\"title\":\"" + "t".repeat(150) + "\"},\"str\"],"
                + "\"selected_option_id\":\"o1\"},{\"id\":\"\"},\"str\",{\"id\":\"g2\",\"selected_option_id\":\" \"}]},"
                + "{\"id\":\"\",\"type\":\"shipping\"},{\"id\":\"m3\",\"type\":\" \"},{\"type\":\"pickup\"},{\"id\":\"m4\"},\"str\","
                + "{\"id\":\"m5\",\"type\":\"pickup\"}]}");

        assertThat(f.methods()).hasSize(2);
        FulfillmentView.Method m1 = f.methods().get(0);
        assertThat(m1.id()).isEqualTo("m1");
        assertThat(m1.type()).isEqualTo("shipping");
        assertThat(m1.lineItemIds()).containsExactly("li_1", "li_2");
        assertThat(m1.destinations()).containsExactly(new FulfillmentView.Destination("d1", Optional.of("US")),
                new FulfillmentView.Destination("d2", Optional.empty()), new FulfillmentView.Destination("d3", Optional.empty()),
                new FulfillmentView.Destination("d4", Optional.of("GB")));
        assertThat(m1.selectedDestinationId()).contains("d1");
        assertThat(m1.groups()).hasSize(2);
        FulfillmentView.Group g1 = m1.groups().get(0);
        assertThat(g1.id()).isEqualTo("g1");
        assertThat(g1.lineItemIds()).containsExactly("li_1");
        assertThat(g1.selectedOptionId()).contains("o1");
        assertThat(g1.options()).containsExactly(new FulfillmentView.Option("o1", "Standard", 500),
                new FulfillmentView.Option("o2", "", 0), new FulfillmentView.Option("o3", "", 0),
                new FulfillmentView.Option("o4", "Fast", 0),
                new FulfillmentView.Option("x".repeat(64), "t".repeat(100), 0));
        FulfillmentView.Group g2 = m1.groups().get(1);
        assertThat(g2.id()).isEqualTo("g2");
        assertThat(g2.options()).isEmpty();
        assertThat(g2.lineItemIds()).isEmpty();
        assertThat(g2.selectedOptionId()).isEmpty();
        FulfillmentView.Method m5 = f.methods().get(1);
        assertThat(m5.id()).isEqualTo("m5");
        assertThat(m5.type()).isEqualTo("pickup");
        assertThat(m5.destinations()).isEmpty();
        assertThat(m5.groups()).isEmpty();
        assertThat(m5.lineItemIds()).isEmpty();
        assertThat(m5.selectedDestinationId()).isEmpty();
    }

    // Covers: 8.1, D4. Kills CheckoutParser.fulfillment:209 and :214 (a missing or malformed fulfillment part is absent; an
    // empty list of methods is a part with none) and the cut of ids and types
    @Test
    void aFulfillmentPart_isAbsent_unlessItIsAnObjectWithAListOfMethods() {
        assertThat(view(checkout("")).fulfillment()).isEmpty();
        assertThat(view(checkout("\"fulfillment\":\"x\"")).fulfillment()).isEmpty();
        assertThat(view(checkout("\"fulfillment\":{}")).fulfillment()).isEmpty();
        assertThat(view(checkout("\"fulfillment\":{\"methods\":{}}")).fulfillment()).isEmpty();
        assertThat(fulfillment("{\"methods\":[]}").methods()).isEmpty();

        FulfillmentView.Method m = fulfillment("{\"methods\":[{\"id\":\"" + "m".repeat(70) + "\",\"type\":\"" + "t".repeat(40)
                + "\",\"line_item_ids\":[\"" + "l".repeat(70) + "\"],\"selected_destination_id\":\"" + "s".repeat(70) + "\"}]}").methods().get(0);
        assertThat(m.id()).isEqualTo("m".repeat(64));
        assertThat(m.type()).isEqualTo("t".repeat(32));
        assertThat(m.lineItemIds()).containsExactly("l".repeat(64));
        assertThat(m.selectedDestinationId()).contains("s".repeat(64));
    }

    // ---------------------------------------------------------------- error bodies (8.1)

    private static boolean envelope(String json) {
        return CheckoutParser.isErrorEnvelope(json.getBytes(StandardCharsets.UTF_8));
    }

    // Covers: 8.1. Kills CheckoutParser.isErrorEnvelope:117, :118, :119 (every part of the test for a UCP error envelope)
    @Test
    void aUcpErrorEnvelope_isStatusErrorWithAMessageThatHasACode() {
        String ok = "{\"ucp\":{\"status\":\"error\"},\"messages\":[{\"code\":\"c\",\"content\":\"x\"}]}";
        assertThat(envelope(ok)).isTrue();
        assertThat(envelope(ok.replace("\"error\"", "\"ok\""))).isFalse();
        assertThat(envelope(ok.replace("\"ucp\":{\"status\":\"error\"}", "\"ucp\":\"error\""))).isFalse();
        assertThat(envelope(ok.replace("\"ucp\":{\"status\":\"error\"},", ""))).isFalse();
        assertThat(envelope(ok.replace("\"ucp\":{\"status\":\"error\"}", "\"ucp\":{}"))).isFalse();
        assertThat(envelope(ok.replace("\"code\":\"c\"", "\"code\":\" \""))).isFalse();
        assertThat(envelope(ok.replace("\"code\":\"c\"", "\"code\":5"))).isFalse();
        assertThat(envelope(ok.replace("\"code\":\"c\",", ""))).isFalse();
        assertThat(envelope(ok.replace("[{\"code\":\"c\",\"content\":\"x\"}]", "[]"))).isFalse();
        assertThat(envelope(ok.replace("[{\"code\":\"c\",\"content\":\"x\"}]", "[\"x\"]"))).isFalse();
        assertThat(envelope(ok.replace("[{\"code\":\"c\",\"content\":\"x\"}]", "\"x\""))).isFalse();
        assertThat(envelope("not json")).isFalse();
    }

    private static CheckoutParser.ErrorBody error(String json) {
        return CheckoutParser.parseError(json.getBytes(StandardCharsets.UTF_8));
    }

    // Covers: 8.1, I8. Kills CheckoutParser.parseError:99, :103 (the code and content of a protocol error: at the top, or in the
    // first message of an envelope; the code lower-cased and cut to 64, the content cleaned and cut to 500; anything else
    // is "unknown" with no content)
    @Test
    void aProtocolErrorBody_givesItsCodeAndContent_orUnknown() {
        assertThat(error("{\"code\":\"Not_Found\",\"content\":\"no such\\nsession\"}"))
                .isEqualTo(new CheckoutParser.ErrorBody("not_found", "no such session"));
        assertThat(error("{\"ucp\":{\"status\":\"error\"},\"messages\":[{\"code\":\"INSUFFICIENT_FUNDS\",\"content\":\"declined\"},{\"code\":\"x\"}]}"))
                .isEqualTo(new CheckoutParser.ErrorBody("insufficient_funds", "declined"));
        assertThat(error("{\"code\":\"top\",\"content\":\"top text\",\"messages\":[{\"code\":\"inner\",\"content\":\"inner text\"}]}"))
                .isEqualTo(new CheckoutParser.ErrorBody("top", "top text"));
        assertThat(error("{\"code\":5,\"content\":5,\"messages\":[{\"code\":\"inner\",\"content\":\"inner text\"}]}"))
                .isEqualTo(new CheckoutParser.ErrorBody("inner", "inner text"));
        assertThat(error("{\"code\":\" \",\"content\":7}")).isEqualTo(new CheckoutParser.ErrorBody("unknown", ""));
        assertThat(error("{\"messages\":[]}")).isEqualTo(new CheckoutParser.ErrorBody("unknown", ""));
        assertThat(error("{\"messages\":[\"x\"]}")).isEqualTo(new CheckoutParser.ErrorBody("unknown", ""));
        assertThat(error("[1]")).isEqualTo(new CheckoutParser.ErrorBody("unknown", ""));
        assertThat(error("not json")).isEqualTo(new CheckoutParser.ErrorBody("unknown", ""));
        assertThat(CheckoutParser.parseError(new byte[] {(byte) 0xC3, 0x28})).isEqualTo(new CheckoutParser.ErrorBody("unknown", ""));
        assertThat(error("{\"code\":\"" + "A".repeat(100) + "\",\"content\":\"" + "y".repeat(600) + "\"}"))
                .isEqualTo(new CheckoutParser.ErrorBody("a".repeat(64), "y".repeat(500)));
    }

    // Covers: Appendix B, H5 C6 (continue_url is read only as a string: any other JSON value reads as none, as a string
    // that is not a URI does). Kills CheckoutParser.continueUrl:160 RemoveConditional (the `instanceof String` test)
    @Test
    void aContinueUrl_thatIsNotAString_readsAsNone() {
        assertThat(view(checkout("\"continue_url\":\"https://shop.example/c/1\"")).continueUrl())
                .contains(new ContinueUrl(URI.create("https://shop.example/c/1")));
        assertThat(view(checkout("\"continue_url\":5")).continueUrl()).isEmpty();
        assertThat(view(checkout("\"continue_url\":{\"url\":\"https://shop.example/c/1\"}")).continueUrl()).isEmpty();
        assertThat(view(checkout("")).continueUrl()).isEmpty();
    }
}
