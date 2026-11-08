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
// TODO(VERIFY): field names (line_items[].item.id, quantity, buyer.email, payment_data) are not confirmed against
// https://ucp.dev/latest/specification/shopping/checkout/rest/ (2026-08-25). Check them in M2 before the HTTP client.
public final class CheckoutWire {

    private CheckoutWire() {
    }

    public static String createBody(CreateCheckout request) {
        Objects.requireNonNull(request, "request");
        return CanonicalJson.write(checkoutObject(request.lines(), request.buyerEmail()));
    }

    /** PUT is a full replacement of buyer and line items (A.1), so it has the same shape as create. */
    public static String updateBody(UpdateCheckout request) {
        Objects.requireNonNull(request, "request");
        return CanonicalJson.write(checkoutObject(request.lines(), request.buyerEmail()));
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
        return new FrozenBody(CanonicalJson.write(Map.of("payment_data", payment)));
    }

    /** The credential token in a {@link #completeBody}, if the body has one. */
    public static Optional<String> paymentToken(FrozenBody body) {
        Objects.requireNonNull(body, "body");
        try {
            Map<String, Object> root = CanonicalJson.readObject(body.json());
            if (root.get("payment_data") instanceof Map<?, ?> payment
                    && payment.get("credential") instanceof Map<?, ?> credential
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
        return CanonicalJson.write(m);
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
