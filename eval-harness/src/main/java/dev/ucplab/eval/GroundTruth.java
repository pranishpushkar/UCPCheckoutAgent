package dev.ucplab.eval;

import dev.ucplab.merchant.LabMerchant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed reads of the merchant's ground truth (13.1, m4-plan section 5): O, the orders from {@code /_lab/orders}, and G,
 * the merchant's own view of a session. Both are read from the merchant directly, never through the proxy, and never
 * from the orchestrator's own report.
 */
public final class GroundTruth {

    /** One row of {@code /_lab/orders}. The list carries no line items or currency, which is why I2 joins G. */
    public record Order(String id, String checkoutId, Optional<String> buyerEmail, long amountMinor) {
        public Order {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(checkoutId, "checkoutId");
            Objects.requireNonNull(buyerEmail, "buyerEmail");
        }
    }

    /** One line of a session as the merchant holds it. */
    public record SessionLine(String itemId, long quantity, long unitPriceMinor) {
    }

    /** A session as the merchant's own GET returns it. */
    public record Session(String id, String status, String currency, List<SessionLine> lines, long totalMinor,
                          Optional<String> orderId) {
        public Session {
            lines = List.copyOf(lines);
        }
    }

    private final LabMerchant merchant;

    public GroundTruth(LabMerchant merchant) {
        this.merchant = Objects.requireNonNull(merchant, "merchant");
    }

    public List<Order> orders() {
        List<Order> out = new ArrayList<>();
        for (Map<String, Object> o : merchant.orders()) {
            out.add(new Order((String) o.get("id"), (String) o.get("checkout_id"),
                    Optional.ofNullable(o.get("buyer_email")).filter(String.class::isInstance).map(String.class::cast),
                    ((Number) o.get("amount")).longValue()));
        }
        return out;
    }

    /** G for one session; empty if the merchant has never heard of it. */
    @SuppressWarnings("unchecked")
    public Optional<Session> session(String sessionId) {
        return merchant.session(sessionId).map(s -> {
            List<SessionLine> lines = new ArrayList<>();
            for (Object li : (List<Object>) s.getOrDefault("line_items", List.of())) {
                Map<String, Object> line = (Map<String, Object>) li;
                Map<String, Object> item = (Map<String, Object>) line.get("item");
                lines.add(new SessionLine((String) item.get("id"), ((Number) line.get("quantity")).longValue(),
                        ((Number) item.get("price")).longValue()));
            }
            long total = 0;
            for (Object t : (List<Object>) s.getOrDefault("totals", List.of())) {
                Map<String, Object> entry = (Map<String, Object>) t;
                if ("total".equals(entry.get("type"))) {
                    total = ((Number) entry.get("amount")).longValue();
                }
            }
            Optional<String> orderId = Optional.ofNullable(s.get("order"))
                    .filter(Map.class::isInstance)
                    .map(o -> (String) ((Map<String, Object>) o).get("id"));
            return new Session((String) s.get("id"), (String) s.get("status"), (String) s.get("currency"), lines,
                    total, orderId);
        });
    }
}
