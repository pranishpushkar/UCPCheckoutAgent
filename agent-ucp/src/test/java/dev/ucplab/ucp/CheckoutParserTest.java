package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.merchant.Buyer;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.ucp.CheckoutParser.Parsed;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CheckoutParserTest {

    /** A real merchant response (captured from MerchantServer; see src/test/resources/merchant). */
    private static byte[] fixture(String name) {
        try (InputStream in = CheckoutParserTest.class.getResourceAsStream("/merchant/" + name + ".json")) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static CheckoutView view(byte[] body) {
        Parsed parsed = CheckoutParser.parseCheckout(body);
        assertThat(parsed).isInstanceOf(Parsed.Checkout.class);
        return ((Parsed.Checkout) parsed).view();
    }

    private static List<String> codes(CheckoutView v) {
        return v.messages().stream().map(MerchantMessage::code).toList();
    }

    /** Minimal valid checkout with one replaceable part, for the negative cases. */
    private static String checkoutJson(String status, String currency, String lines, String totals, String extra) {
        return "{\"id\":\"chk_1\",\"status\":" + status + ",\"currency\":" + currency + ",\"line_items\":" + lines
                + ",\"totals\":" + totals + extra + "}";
    }

    private static final String LINES = "[{\"id\":\"li_1\",\"item\":{\"id\":\"item_123\",\"price\":2500},\"quantity\":2}]";
    private static final String TOTALS = "[{\"type\":\"subtotal\",\"amount\":5000},{\"type\":\"tax\",\"amount\":400},"
            + "{\"type\":\"total\",\"amount\":5400}]";

    static Stream<Arguments> checkoutFixtures() {
        return Stream.of(
                // fixture, id, status, total, order id, message codes
                Arguments.of("create_ready", "chk_1001", CheckoutStatus.READY_FOR_COMPLETE, 5400L, Optional.empty(),
                        List.of()),
                Arguments.of("create_incomplete_no_buyer", "chk_1002", CheckoutStatus.INCOMPLETE, 1296L,
                        Optional.empty(), List.of("missing")),
                Arguments.of("create_partial_unavailable", "chk_1003", CheckoutStatus.READY_FOR_COMPLETE, 2700L,
                        Optional.empty(), List.of("item_unavailable")),
                Arguments.of("get_ready", "chk_1001", CheckoutStatus.READY_FOR_COMPLETE, 5400L, Optional.empty(),
                        List.of()),
                Arguments.of("get_in_progress", "chk_1007", CheckoutStatus.COMPLETE_IN_PROGRESS, 2700L,
                        Optional.empty(), List.of()),
                Arguments.of("complete_completed", "chk_1006", CheckoutStatus.COMPLETED, 5400L, Optional.of("ord_5001"),
                        List.of()),
                Arguments.of("complete_price_changed", "chk_1001", CheckoutStatus.INCOMPLETE, 5616L, Optional.empty(),
                        List.of("price_changed")),
                Arguments.of("complete_declined", "chk_1004", CheckoutStatus.READY_FOR_COMPLETE, 1296L,
                        Optional.empty(), List.of("payment_declined")),
                Arguments.of("complete_out_of_stock", "chk_1005", CheckoutStatus.READY_FOR_COMPLETE, 2592L,
                        Optional.empty(), List.of("out_of_stock")),
                Arguments.of("cancel_canceled", "chk_1008", CheckoutStatus.CANCELED, 1296L, Optional.empty(),
                        List.of()));
    }

    // Covers: 8.1 (a 200 or 201 with a valid body is Ok), Appendix B (CheckoutView), I2 (totals and the line price
    // come from the merchant as integer minor units); built from the merchant's real responses
    @ParameterizedTest(name = "{0}")
    @MethodSource("checkoutFixtures")
    void parseCheckout_readsRealMerchantResponse(
            String name, String id, CheckoutStatus status, long total, Optional<String> order, List<String> codes) {
        CheckoutView v = view(fixture(name));

        assertThat(v.id().value()).isEqualTo(id);
        assertThat(v.status()).isEqualTo(status);
        assertThat(v.currency()).isEqualTo("USD");
        assertThat(v.totalMinor()).isEqualTo(total);
        assertThat(v.order().map(o -> o.id())).isEqualTo(order);
        assertThat(codes(v)).isEqualTo(codes);
    }

    // Covers: 8.4, I2, decision L (lines carry the merchant's unit price and line id; the buyer is read; subtotal, tax
    // and total are read by type)
    @Test
    void parseCheckout_readsLinesAndTotalsByType() {
        CheckoutView v = view(fixture("create_ready"));

        assertThat(v.lines()).containsExactly(new LineView("item_123", 2, 2500, Optional.of("li_1")));
        assertThat(v.buyer()).contains(Buyer.ofEmail("a@example.com"));
        assertThat(v.subtotalMinor()).isEqualTo(5000);
        assertThat(v.taxMinor()).isEqualTo(400);
        assertThat(v.totalMinor()).isEqualTo(5400);
    }

    // Covers: 8.1 (messages are read with type, code, path, content and severity)
    @Test
    void parseCheckout_readsMessageFields() {
        CheckoutView v = view(fixture("complete_declined"));

        assertThat(v.messages()).singleElement().isEqualTo(new MerchantMessage("error", "payment_declined",
                Optional.of("$.payment"), "Payment was declined: card_declined", Optional.of("recoverable")));
    }

    // Covers: 8.1, decision N (the all-unavailable create envelope has no id: CreateRejected, never a checkout)
    @Test
    void parseCheckout_returnsCreateRejected_forEnvelopeWithoutId() {
        Parsed parsed = CheckoutParser.parseCheckout(fixture("create_all_unavailable"));

        assertThat(parsed).isInstanceOfSatisfying(Parsed.CreateRejected.class, r ->
                assertThat(r.messages()).singleElement().satisfies(m -> {
                    assertThat(m.code()).isEqualTo("out_of_stock");
                    assertThat(m.severity()).contains("unrecoverable");
                }));
    }

    // Covers: 8.4 (unknown fields are ignored, whatever their shape)
    @Test
    void parseCheckout_ignoresUnknownFields() {
        String json = checkoutJson("\"ready_for_complete\"", "\"USD\"", LINES, TOTALS,
                ",\"extra\":\"x\",\"nested\":{\"a\":[1,2,{\"b\":true}]},\"links\":[]");

        assertThat(view(bytes(json)).totalMinor()).isEqualTo(5400);
    }

    // Covers: 8.4 (null, decimals and unknown fields are ignored wherever the client does not use them), decision P2
    @Test
    void parseCheckout_ignoresNullAndDecimalInUnusedFields() {
        String json = checkoutJson("\"ready_for_complete\"", "\"USD\"",
                "[{\"id\":\"li_1\",\"item\":{\"id\":\"item_123\",\"price\":2500,\"weight\":1.25,\"image\":null},"
                        + "\"quantity\":2,\"discount_rate\":0.5}]",
                TOTALS, ",\"continue_url\":null,\"loyalty\":{\"points\":12.5,\"tier\":null},\"tags\":[null,1.5]");

        CheckoutView v = view(bytes(json));

        assertThat(v.totalMinor()).isEqualTo(5400);
        assertThat(v.lines()).containsExactly(new LineView("item_123", 2, 2500, Optional.of("li_1")));
    }

    // Covers: 8.1 (subtotal and tax are optional in the schema and read as 0; the total is required)
    @Test
    void parseCheckout_readsMissingSubtotalAndTaxAsZero() {
        String json = checkoutJson("\"ready_for_complete\"", "\"USD\"", LINES, "[{\"type\":\"total\",\"amount\":5000}]",
                "");

        CheckoutView v = view(bytes(json));

        assertThat(v.subtotalMinor()).isZero();
        assertThat(v.taxMinor()).isZero();
        assertThat(v.totalMinor()).isEqualTo(5000);
    }

    // Covers: 8.1 (an info message has no code in the schema; it reads with an empty code)
    @Test
    void parseCheckout_readsInfoMessageWithoutCode() {
        String json = checkoutJson("\"incomplete\"", "\"USD\"", LINES, TOTALS,
                ",\"messages\":[{\"type\":\"info\",\"content\":\"hello\"}]");

        assertThat(view(bytes(json)).messages()).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo("info");
            assertThat(m.code()).isEmpty();
        });
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("unknown status", checkoutJson("\"weird\"", "\"USD\"", LINES, TOTALS, "")),
                // The raw 2026-08-25 schema has a sixth status that CheckoutStatus does not model (spec-notes.md).
                Arguments.of("requires_escalation", checkoutJson("\"requires_escalation\"", "\"USD\"", LINES, TOTALS, "")),
                Arguments.of("status not a string", checkoutJson("3", "\"USD\"", LINES, TOTALS, "")),
                Arguments.of("currency missing", "{\"id\":\"chk_1\",\"status\":\"incomplete\",\"line_items\":" + LINES
                        + ",\"totals\":" + TOTALS + "}"),
                Arguments.of("id blank", "{\"id\":\" \",\"status\":\"incomplete\",\"currency\":\"USD\",\"line_items\":"
                        + LINES + ",\"totals\":" + TOTALS + "}"),
                Arguments.of("id missing, no error envelope", "{\"status\":\"incomplete\",\"currency\":\"USD\"}"),
                Arguments.of("line_items missing", "{\"id\":\"chk_1\",\"status\":\"incomplete\",\"currency\":\"USD\","
                        + "\"totals\":" + TOTALS + "}"),
                Arguments.of("totals missing", "{\"id\":\"chk_1\",\"status\":\"incomplete\",\"currency\":\"USD\","
                        + "\"line_items\":" + LINES + "}"),
                Arguments.of("total entry missing", checkoutJson("\"incomplete\"", "\"USD\"", LINES,
                        "[{\"type\":\"subtotal\",\"amount\":5000}]", "")),
                Arguments.of("duplicate total", checkoutJson("\"incomplete\"", "\"USD\"", LINES,
                        "[{\"type\":\"total\",\"amount\":1},{\"type\":\"total\",\"amount\":2}]", "")),
                Arguments.of("negative total", checkoutJson("\"incomplete\"", "\"USD\"", LINES,
                        "[{\"type\":\"total\",\"amount\":-1}]", "")),
                Arguments.of("amount is a string", checkoutJson("\"incomplete\"", "\"USD\"", LINES,
                        "[{\"type\":\"total\",\"amount\":\"5400\"}]", "")),
                Arguments.of("amount is a decimal", checkoutJson("\"incomplete\"", "\"USD\"", LINES,
                        "[{\"type\":\"total\",\"amount\":54.00}]", "")),
                Arguments.of("quantity zero", checkoutJson("\"incomplete\"", "\"USD\"",
                        "[{\"item\":{\"id\":\"item_123\",\"price\":2500},\"quantity\":0}]", TOTALS, "")),
                Arguments.of("quantity missing", checkoutJson("\"incomplete\"", "\"USD\"",
                        "[{\"item\":{\"id\":\"item_123\",\"price\":2500}}]", TOTALS, "")),
                Arguments.of("item price missing", checkoutJson("\"incomplete\"", "\"USD\"",
                        "[{\"item\":{\"id\":\"item_123\"},\"quantity\":1}]", TOTALS, "")),
                Arguments.of("item id missing", checkoutJson("\"incomplete\"", "\"USD\"",
                        "[{\"item\":{\"price\":2500},\"quantity\":1}]", TOTALS, "")),
                Arguments.of("line without item", checkoutJson("\"incomplete\"", "\"USD\"",
                        "[{\"quantity\":1}]", TOTALS, "")),
                Arguments.of("unknown message type", checkoutJson("\"incomplete\"", "\"USD\"", LINES, TOTALS,
                        ",\"messages\":[{\"type\":\"banner\",\"code\":\"x\",\"content\":\"y\"}]")),
                Arguments.of("error message without code", checkoutJson("\"incomplete\"", "\"USD\"", LINES, TOTALS,
                        ",\"messages\":[{\"type\":\"error\",\"content\":\"y\"}]")),
                Arguments.of("message without content", checkoutJson("\"incomplete\"", "\"USD\"", LINES, TOTALS,
                        ",\"messages\":[{\"type\":\"warning\",\"code\":\"x\"}]")),
                Arguments.of("order without id", checkoutJson("\"completed\"", "\"USD\"", LINES, TOTALS,
                        ",\"order\":{\"permalink_url\":\"https://m.example/o\"}")),
                Arguments.of("order not an object", checkoutJson("\"completed\"", "\"USD\"", LINES, TOTALS,
                        ",\"order\":\"ord_1\"")),
                Arguments.of("error envelope without messages",
                        "{\"ucp\":{\"version\":\"2026-08-25\",\"status\":\"error\"}}"),
                Arguments.of("null in a field we use", checkoutJson("\"incomplete\"", "\"USD\"",
                        "[{\"item\":{\"id\":\"item_123\",\"price\":null},\"quantity\":1}]", TOTALS, "")),
                Arguments.of("not an object", "[1,2,3]"),
                Arguments.of("truncated JSON", "{\"id\":\"chk_1\",\"status\":\"incompl"),
                Arguments.of("not JSON", "<html>502 Bad Gateway</html>"),
                Arguments.of("empty body", ""));
    }

    // Covers: 8.1, decision B (an unknown status, a missing required field or an unparseable body is Invalid; the
    // gateway makes it Uncertain on a mutating call and ProtocolError on get)
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void parseCheckout_isInvalid_whenBodyCannotBeTrusted(String name, String json) {
        assertThat(CheckoutParser.parseCheckout(bytes(json))).isInstanceOf(Parsed.Invalid.class);
    }

    // Covers: 8.1 (a body that is not valid UTF-8 is unparseable)
    @Test
    void parseCheckout_isInvalid_forMalformedUtf8() {
        byte[] body = new byte[] {'{', '"', 'i', 'd', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};

        assertThat(CheckoutParser.parseCheckout(body)).isInstanceOf(Parsed.Invalid.class);
    }

    // Covers: I8 (an Invalid reason names the field and never repeats merchant text)
    @Test
    void parseCheckout_invalidReason_neverRepeatsValues() {
        Parsed parsed = CheckoutParser.parseCheckout(bytes(
                checkoutJson("\"secret_canary_status\"", "\"USD\"", LINES, TOTALS, "")));

        assertThat(parsed).isInstanceOfSatisfying(Parsed.Invalid.class,
                i -> assertThat(i.reason()).doesNotContain("canary"));
    }

    // Covers: 8.1 (protocol error bodies: code and content of the 404, 409 and 400 the merchant really sends)
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"error_not_found", "error_idempotency_conflict", "error_missing_header"})
    void parseError_readsCodeAndContent_ofRealMerchantErrors(String name) {
        CheckoutParser.ErrorBody e = CheckoutParser.parseError(fixture(name));

        assertThat(e.code()).isEqualTo(switch (name) {
            case "error_not_found" -> "not_found";
            case "error_idempotency_conflict" -> "idempotency_key_reuse";
            default -> "missing_header";
        });
        assertThat(e.content()).isNotBlank();
    }

    // Covers: 8.1 (a protocol error body that is not {code, content} gives code unknown; it never throws)
    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[]", "{\"code\":5}", "{\"content\":\"x\"}"})
    void parseError_fallsBackToUnknown_whenBodyHasNoCode(String json) {
        assertThat(CheckoutParser.parseError(bytes(json)).code()).isEqualTo("unknown");
    }

    // Covers: I8, 11 (error content is untrusted: control characters are stripped and it is cut to 500 characters)
    @Test
    void parseError_stripsControlCharactersAndCapsContent() {
        String long600 = "a".repeat(600);

        CheckoutParser.ErrorBody e = CheckoutParser.parseError(
                bytes("{\"code\":\"bad\",\"content\":\"line1\\nline2\\u0007" + long600 + "\"}"));

        assertThat(e.content()).hasSize(500).startsWith("line1 line2 ").doesNotContain("\n");
    }
}
