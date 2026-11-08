package dev.ucplab.core.merchant.fake;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A fault injected into one call of {@link FaultyMerchantModel} (section 14, level 2). Delivery faults decide whether
 * and when the request reaches the merchant and what the client sees; at most one applies to a call. Merchant-side
 * changes ({@link PriceChange}, {@link StockChange}) are applied just before the request is processed and combine
 * with a delivery fault.
 */
public sealed interface Fault {

    /** Whether this fault decides delivery (as opposed to changing merchant state). */
    default boolean isDelivery() {
        return true;
    }

    /** Connection refused before any bytes were written: the merchant sees nothing; the client gets NotSent (8.1). */
    record NotSent() implements Fault {}

    /** {@code fail_before_forward} with a status: the merchant sees nothing; the client gets ProtocolError. */
    record Reject(int httpStatus, String code, Optional<Duration> retryAfter) implements Fault {
        public Reject {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(retryAfter, "retryAfter");
        }
    }

    /** {@code forward_then_drop_response}: the merchant commits; the client gets Uncertain (5.4, S2). */
    record CommitThenDrop() implements Fault {}

    /** {@code forward_then_delay_response}: the merchant commits; the response comes after the client timeout (S3). */
    record DelayResponse() implements Fault {}

    /**
     * {@code delay} at the proxy: the request is held and reaches the merchant only once the clock has passed
     * {@code hold}; the client times out and gets Uncertain (S17).
     */
    record DelayForward(Duration hold) implements Fault {
        public DelayForward {
            Objects.requireNonNull(hold, "hold");
        }
    }

    /**
     * Complete only: the merchant takes {@code latency} to charge (A.3 {@code payment_latency_ms}) and shows
     * {@code complete_in_progress} meanwhile; the client times out and gets Uncertain. Other calls ignore it.
     */
    record HoldInProgress(Duration latency) implements Fault {
        public HoldInProgress {
            Objects.requireNonNull(latency, "latency");
        }
    }

    /** {@code duplicate_request}: delivered twice with the same key; the client gets the first response (S13). */
    record Duplicate() implements Fault {}

    /** The merchant changes an item's price just before processing the call (S5, S16). */
    record PriceChange(String itemId, long newPriceMinor) implements Fault {
        public PriceChange {
            Objects.requireNonNull(itemId, "itemId");
        }

        @Override
        public boolean isDelivery() {
            return false;
        }
    }

    /** The merchant changes an item's stock just before processing the call (S6, S21). */
    record StockChange(String itemId, int newStock) implements Fault {
        public StockChange {
            Objects.requireNonNull(itemId, "itemId");
        }

        @Override
        public boolean isDelivery() {
            return false;
        }
    }
}
