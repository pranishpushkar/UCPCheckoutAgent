package dev.ucplab.ucp;

import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.OrderRef;
import dev.ucplab.core.merchant.SessionId;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads merchant responses into core types (Appendix B, 8.1, 8.4). Pure: it classifies nothing and calls nothing.
 * The gateway turns {@link Parsed.Invalid} into {@code Uncertain} for a mutating call and into {@code ProtocolError}
 * for {@code get} (decision B), and {@link Parsed.CreateRejected} into {@code Rejected} for a create only.
 *
 * <p>A response is invalid if a field this client needs is missing or has the wrong type: {@code id}, {@code status},
 * {@code currency}, {@code line_items} (each with {@code item.id}, {@code item.price} and {@code quantity}), and
 * {@code totals} with exactly one {@code total}. Unknown fields are ignored. {@code subtotal} and {@code tax} are
 * optional in the schema; when absent they read as 0. Money is an integer in minor units, never negative here.
 * Fields this client does not use ({@code links}, {@code ucp}, {@code buyer}) are never required.
 *
 * <p>Limit (TODO): the JSON reader is core's {@link CanonicalJson}, which refuses {@code null} and non-integer numbers
 * anywhere in a document, so a merchant that sends either in a field we ignore makes the whole body invalid.
 */
public final class CheckoutParser {

    private static final int MAX_ERROR_CONTENT = 500;

    private CheckoutParser() {
    }

    /** The result of reading a 200 or 201 body. */
    public sealed interface Parsed {

        /** A checkout session. */
        record Checkout(CheckoutView view) implements Parsed {
            public Checkout {
                Objects.requireNonNull(view, "view");
            }
        }

        /** The envelope of a create with every item unavailable: {@code ucp.status} error, no session id. */
        record CreateRejected(List<MerchantMessage> messages) implements Parsed {
            public CreateRejected {
                messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            }
        }

        /** Not usable. {@code reason} names the field, never its value. */
        record Invalid(String reason) implements Parsed {
            public Invalid {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /** The {@code {code, content}} body of a protocol error. Both are untrusted merchant text. */
    public record ErrorBody(String code, String content) {
        public ErrorBody {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(content, "content");
        }
    }

    /** Reads a checkout session, or the all-unavailable create envelope, from a response body. */
    public static Parsed parseCheckout(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            Map<String, Object> root = CanonicalJson.readObject(decode(body));
            if (!root.containsKey("id")) {
                return rejectedEnvelope(root);
            }
            return new Parsed.Checkout(checkout(root));
        } catch (Bad | IllegalArgumentException | CharacterCodingException e) {
            return new Parsed.Invalid(e instanceof Bad ? e.getMessage() : "unparseable body");
        }
    }

    /**
     * Reads the {@code code} and {@code content} of a protocol error body. Never throws: a body that is not that shape
     * gives code {@code unknown} and empty content. The content is stripped of control characters and cut to 500
     * characters, because it ends up in events and logs.
     */
    public static ErrorBody parseError(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            Map<String, Object> root = CanonicalJson.readObject(decode(body));
            String code = root.get("code") instanceof String s && !s.isBlank() ? clean(s, 64) : "unknown";
            String content = root.get("content") instanceof String s ? clean(s, MAX_ERROR_CONTENT) : "";
            return new ErrorBody(code, content);
        } catch (IllegalArgumentException | CharacterCodingException e) {
            return new ErrorBody("unknown", "");
        }
    }

    private static Parsed rejectedEnvelope(Map<String, Object> root) {
        if (root.get("ucp") instanceof Map<?, ?> ucp && "error".equals(ucp.get("status"))) {
            List<MerchantMessage> messages = messages(root.get("messages"));
            if (messages.isEmpty()) {
                throw new Bad("error envelope without messages");
            }
            return new Parsed.CreateRejected(messages);
        }
        throw new Bad("id missing");
    }

    private static CheckoutView checkout(Map<String, Object> root) {
        SessionId id = new SessionId(text(root, "id"));
        CheckoutStatus status;
        try {
            status = CheckoutStatus.parse(text(root, "status"));
        } catch (IllegalArgumentException e) {
            throw new Bad("unknown status");
        }
        String currency = text(root, "currency");
        List<LineView> lines = lines(root.get("line_items"));
        Totals totals = totals(root.get("totals"));
        Optional<OrderRef> order = order(root.get("order"));
        return new CheckoutView(id, status, currency, lines, totals.subtotal, totals.tax, totals.total, order,
                messages(root.get("messages")));
    }

    private static List<LineView> lines(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new Bad("line_items missing");
        }
        List<LineView> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> line) || !(line.get("item") instanceof Map<?, ?> item)) {
                throw new Bad("line item shape");
            }
            long quantity = integer(line.get("quantity"), "quantity");
            if (quantity < 1 || quantity > Integer.MAX_VALUE) {
                throw new Bad("quantity out of range");
            }
            out.add(new LineView(text(item, "id"), (int) quantity, minor(item.get("price"), "item.price")));
        }
        return out;
    }

    private record Totals(long subtotal, long tax, long total) {}

    private static Totals totals(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new Bad("totals missing");
        }
        Long subtotal = null;
        Long tax = null;
        Long total = null;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> entry)) {
                throw new Bad("totals entry shape");
            }
            String type = text(entry, "type");
            long amount = integer(entry.get("amount"), "totals.amount");
            switch (type) {
                case "subtotal" -> subtotal = once(subtotal, amount, type);
                case "tax" -> tax = once(tax, amount, type);
                case "total" -> total = once(total, amount, type);
                default -> { }
            }
        }
        if (total == null) {
            throw new Bad("total missing");
        }
        return new Totals(subtotal == null ? 0 : subtotal, tax == null ? 0 : tax, total);
    }

    private static long once(Long current, long amount, String type) {
        if (current != null) {
            throw new Bad("duplicate " + type);
        }
        if (amount < 0) {
            throw new Bad("negative " + type);
        }
        return amount;
    }

    private static Optional<OrderRef> order(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof Map<?, ?> order)) {
            throw new Bad("order shape");
        }
        return Optional.of(new OrderRef(text(order, "id")));
    }

    private static List<MerchantMessage> messages(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new Bad("messages shape");
        }
        List<MerchantMessage> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new Bad("message shape");
            }
            String type = text(m, "type");
            if (!type.equals("error") && !type.equals("warning") && !type.equals("info")) {
                throw new Bad("unknown message type");
            }
            // The schema requires a code for errors and warnings; an info message has none.
            String code = type.equals("info") && !m.containsKey("code") ? "" : text(m, "code");
            out.add(new MerchantMessage(type, code, optionalText(m, "path"), text(m, "content"),
                    optionalText(m, "severity")));
        }
        return out;
    }

    private static String text(Map<?, ?> map, String key) {
        if (map.get(key) instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new Bad(key + " missing");
    }

    private static Optional<String> optionalText(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return Optional.empty();
        }
        if (v instanceof String s) {
            return Optional.of(s);
        }
        throw new Bad(key + " shape");
    }

    private static long integer(Object value, String name) {
        if (value instanceof Long n) {
            return n;
        }
        throw new Bad(name + " not an integer");
    }

    private static long minor(Object value, String name) {
        long n = integer(value, name);
        if (n < 0) {
            throw new Bad(name + " negative");
        }
        return n;
    }

    private static String decode(byte[] body) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString();
    }

    private static String clean(String s, int max) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length() && out.length() < max; i++) {
            char c = s.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString();
    }

    /** An invalid field; carries only the field name. No stack trace: it is control flow inside this class. */
    private static final class Bad extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Bad(String reason) {
            super(reason, null, false, false);
        }
    }
}
