package dev.ucplab.core.intent;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The handoff follow-up of a HANDED_OFF intent (7.6, section 10 V10): {@code until} ends the window
 * ({@code handoff_watch_until}), {@code nextReadAt} is when the next read is due ({@code handoff_next_read_at}), and
 * {@code storeOrder} is the order the store reported ({@code store_order_id}, {@code store_order_label}). The follow-up
 * is watching while {@code until} is set, and a next read is set exactly then; it never changes the intent's state.
 */
public record HandoffWatch(Optional<Instant> until, Optional<Instant> nextReadAt, Optional<StoreOrder> storeOrder) {

    /** No follow-up: every intent that was never handed off, and one handed off before V10. */
    public static final HandoffWatch NONE = new HandoffWatch(Optional.empty(), Optional.empty(), Optional.empty());

    /** The follow-up window, from the move to HANDED_OFF (7.6). It binds: the schedule makes about 52 reads in it. */
    public static final Duration WINDOW = Duration.ofHours(24);

    /** The cap on counted reads, a backstop behind the window (7.6). */
    public static final int MAX_READS = 60;

    /** The most follow-up reads one worker pass makes at one merchant (7.6, 8.3): the gateway's get takes no token. */
    public static final int READS_PER_MERCHANT_PER_PASS = 2;

    /** The first reads, in minutes after the handoff; then one every {@link #EVERY} (7.6). */
    private static final long[] FIRST_MINUTES = {1, 2, 4, 8, 16, 30};

    private static final Duration EVERY = Duration.ofMinutes(30);

    public HandoffWatch {
        Objects.requireNonNull(until, "until");
        Objects.requireNonNull(nextReadAt, "nextReadAt");
        Objects.requireNonNull(storeOrder, "storeOrder");
        if (until.isPresent() != nextReadAt.isPresent()) {
            throw new IllegalArgumentException("7.6: a follow-up has both a window and a next read, or neither");
        }
    }

    public boolean watching() {
        return until.isPresent();
    }

    /** The follow-up the move to HANDED_OFF opens at {@code handedOffAt} (7.2 hand_off): its window and its first read. */
    public static HandoffWatch opened(Instant handedOffAt) {
        return new HandoffWatch(Optional.of(handedOffAt.plus(WINDOW)), Optional.of(handedOffAt.plus(offset(0))),
                Optional.empty());
    }

    /**
     * The same window with its next read set after {@code counted} counted reads, at {@code now}: the read the schedule
     * places there, or, when that time has passed (reads were late, a NotSent), the schedule's gap from now. Never later
     * than the window's end, where the step ends the follow-up.
     */
    public HandoffWatch nextRead(int counted, Instant now) {
        Instant end = until.orElseThrow(() -> new IllegalStateException("no follow-up is watching"));
        Instant handedOffAt = end.minus(WINDOW);
        Instant scheduled = handedOffAt.plus(offset(counted));
        Instant next = scheduled.isAfter(now) ? scheduled : now.plus(offset(counted).minus(offset(counted - 1)));
        return new HandoffWatch(until, Optional.of(next.isAfter(end) ? end : next), storeOrder);
    }

    /** The follow-up ended: no window, no next read; the store's order, if one was seen, stays. */
    public HandoffWatch ended(Optional<StoreOrder> order) {
        return new HandoffWatch(Optional.empty(), Optional.empty(), order);
    }

    /** When the read with index {@code n} (from 0) is due after the handoff; index -1 is the handoff itself. */
    private static Duration offset(int n) {
        if (n < 0) {
            return Duration.ZERO;
        }
        if (n < FIRST_MINUTES.length) {
            return Duration.ofMinutes(FIRST_MINUTES[n]);
        }
        return Duration.ofMinutes(FIRST_MINUTES[FIRST_MINUTES.length - 1])
                .plus(EVERY.multipliedBy(n - FIRST_MINUTES.length + 1L));
    }
}
