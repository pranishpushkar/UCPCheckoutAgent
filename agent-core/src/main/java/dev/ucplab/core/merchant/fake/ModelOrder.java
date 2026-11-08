package dev.ucplab.core.merchant.fake;

import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.SessionId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** An order the model created: the ground truth for I1 and I2, like {@code GET /_lab/orders} (A.3). */
public record ModelOrder(
        String orderId, SessionId sessionId, List<LineView> lines, String currency, long totalMinor, Instant createdAt) {

    public ModelOrder {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(sessionId, "sessionId");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
