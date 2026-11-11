package dev.ucplab.core.merchant;

import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.json.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical JSON bodies of the mutating checkout calls (8.4). Equal requests give byte-identical bodies, which is what
 * makes a resend under the same {@code Idempotency-Key} safe (A.1: a different body returns 409). The HTTP gateway
 * sends these bytes, {@code merchant_attempt.request_body} stores them, and FaultyMerchantModel compares them.
 */
// Field names were checked in M2 against the 2026-08-25 OpenAPI and checkout schemas (docs/spec-notes.md): line_items[]
// with item.id and quantity, buyer.email, and the complete payment in payment.instruments[].
public final class CheckoutWire {

    private CheckoutWire() {
    }

    public static String createBody(CreateCheckout request) {
        Objects.requireNonNull(request, "request");
        return CanonicalJson.write(checkoutObject(request.lines(), request.buyerEmail()));
    }

    /**
     * PUT is a full replacement of buyer and line items (A.1). It has the shape of create, plus the merchant's line id
     * on each line that has one and every buyer field (8.4).
     */
    public static String updateBody(UpdateCheckout request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line_items", request.lines().stream().map(CheckoutWire::updateLineObject).toList());
        request.buyer().map(CheckoutWire::buyerObject).filter(b -> !b.isEmpty()).ifPresent(b -> m.put("buyer", b));
        return CanonicalJson.write(m);
    }

    /** The {@code complete} body, built once per approval and frozen (7.4, I7). */
    public static FrozenBody completeBody(PaymentInstrument instrument) {
        Objects.requireNonNull(instrument, "instrument");
        Map<String, Object> credential = new LinkedHashMap<>();
        credential.put("type", instrument.credentialType());
        credential.put("token", instrument.token());
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("id", instrument.id());
        payment.put("handler_id", instrument.handlerId());
        payment.put("type", instrument.type());
        payment.put("credential", credential);
        return new FrozenBody(CanonicalJson.write(Map.of("payment", Map.of("instruments", List.of(payment)))));
    }

    /** The credential token in a {@link #completeBody}, if the body has one. */
    public static Optional<String> paymentToken(FrozenBody body) {
        Objects.requireNonNull(body, "body");
        try {
            Map<String, Object> root = CanonicalJson.readObject(body.json());
            if (root.get("payment") instanceof Map<?, ?> payment
                    && payment.get("instruments") instanceof List<?> instruments
                    && !instruments.isEmpty()
                    && instruments.get(0) instanceof Map<?, ?> instrument
                    && instrument.get("credential") instanceof Map<?, ?> credential
                    && credential.get("token") instanceof String token) {
                return Optional.of(token);
            }
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * A short canonical summary of a merchant response for {@code merchant_attempt.response_json}: status, totals,
     * order id and message codes. It never contains request credentials.
     */
    public static String responseSummary(CheckoutView view) {
        Objects.requireNonNull(view, "view");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", view.id().value());
        m.put("status", view.status().wire());
        m.put("currency", view.currency());
        m.put("total_minor", view.totalMinor());
        view.order().ifPresent(o -> m.put("order_id", o.id()));
        m.put("message_codes", view.messages().stream().map(MerchantMessage::code).toList());
        m.put("lines", view.lines().stream().map(l -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("item_id", l.itemId());
            l.lineId().ifPresent(id -> line.put("line_id", id));
            line.put("quantity", l.quantity());
            line.put("unit_price_minor", l.unitPriceMinor());
            return line;
        }).toList());
        view.buyer().map(CheckoutWire::buyerObject).filter(b -> !b.isEmpty()).ifPresent(b -> m.put("buyer", b));
        return CanonicalJson.write(m);
    }

    /**
     * The lines and buyer of the view a {@link #responseSummary} was made from, so an update can resend them (8.4).
     * Empty if the text is not such a summary or has no usable lines. Never throws.
     */
    public static Optional<QuoteShape> quoteShape(String summaryJson) {
        Objects.requireNonNull(summaryJson, "summaryJson");
        try {
            Map<String, Object> root = CanonicalJson.readObject(summaryJson);
            if (!(root.get("lines") instanceof List<?> raw)) {
                return Optional.empty();
            }
            List<LineView> lines = new java.util.ArrayList<>();
            for (Object o : raw) {
                if (!(o instanceof Map<?, ?> l) || !(l.get("item_id") instanceof String item)
                        || !(l.get("quantity") instanceof Long qty) || !(l.get("unit_price_minor") instanceof Long price)) {
                    return Optional.empty();
                }
                lines.add(new LineView(item, Math.toIntExact(qty), price,
                        l.get("line_id") instanceof String id ? Optional.of(id) : Optional.empty()));
            }
            Optional<Buyer> buyer = root.get("buyer") instanceof Map<?, ?> b ? Optional.of(new Buyer(text(b, "email"),
                    text(b, "first_name"), text(b, "last_name"), text(b, "phone_number"))) : Optional.empty();
            return Optional.of(new QuoteShape(lines, buyer));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> text(Map<?, ?> map, String key) {
        return map.get(key) instanceof String s ? Optional.of(s) : Optional.empty();
    }

    private static Map<String, Object> buyerObject(Buyer buyer) {
        Map<String, Object> m = new LinkedHashMap<>();
        buyer.email().ifPresent(v -> m.put("email", v));
        buyer.firstName().ifPresent(v -> m.put("first_name", v));
        buyer.lastName().ifPresent(v -> m.put("last_name", v));
        buyer.phoneNumber().ifPresent(v -> m.put("phone_number", v));
        return m;
    }

    private static Map<String, Object> updateLineObject(UpdateLine line) {
        Map<String, Object> m = new LinkedHashMap<>();
        line.lineId().ifPresent(id -> m.put("id", id));
        m.put("item", Map.of("id", line.itemId()));
        m.put("quantity", line.quantity());
        return m;
    }

    private static Map<String, Object> checkoutObject(List<ProposedLine> lines, Optional<String> buyerEmail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line_items", lines.stream().map(CheckoutWire::lineObject).toList());
        buyerEmail.ifPresent(email -> m.put("buyer", Map.of("email", email)));
        return m;
    }

    private static Map<String, Object> lineObject(ProposedLine line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", Map.of("id", line.itemId()));
        m.put("quantity", line.quantity());
        return m;
    }
}
