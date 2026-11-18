package dev.ucplab.core.intent;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The handoff follow-up of a HANDED_OFF intent (7.6, section 10 V10): {@code until} ends the window
 * ({@code handoff_watch_until}), {@code nextReadAt} is when the next read is due ({@code handoff_next_read_at}), and
 * {@code storeOrder} is the order the store reported ({@code store_order_id}, {@code store_order_label}). The follow-up
 * is watching while {@code until} is set; it never changes the intent's state.
 */
public record HandoffWatch(Optional<Instant> until, Optional<Instant> nextReadAt, Optional<StoreOrder> storeOrder) {

    /** No follow-up: every intent that was never handed off, and one handed off before V10. */
    public static final HandoffWatch NONE = new HandoffWatch(Optional.empty(), Optional.empty(), Optional.empty());

    public HandoffWatch {
        Objects.requireNonNull(until, "until");
        Objects.requireNonNull(nextReadAt, "nextReadAt");
        Objects.requireNonNull(storeOrder, "storeOrder");
    }

    public boolean watching() {
        return until.isPresent();
    }
}
