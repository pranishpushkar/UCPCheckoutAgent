package dev.ucplab.core.orchestrator;

import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.time.Duration;
import java.util.Objects;

/**
 * One entry of a generated {@link Schedule} (section 14, level 2). User actions and merchant-side events run in order;
 * time passes only at {@link Wait}. {@link CallFault}s are installed before the first entry runs, because they target
 * the nth call of a kind over the whole run. A {@link Crash} arms the current worker to die at its next such call.
 */
sealed interface ScheduleEntry {

    /** The user (through the model) proposes the slot's fixed basket (6.2 propose_purchase); a repeat tests 7.7. */
    record Propose(int slot) implements ScheduleEntry {
        @Override
        public String toString() {
            return "propose(" + slot + ")";
        }
    }

    /** The user approves the slot's pending approval with its current token, if one is pending. */
    record Approve(int slot) implements ScheduleEntry {
        @Override
        public String toString() {
            return "approve(" + slot + ")";
        }
    }

    /** The user replays the token of an earlier approval of the slot against the current pending one (I3). */
    record ApproveStale(int slot) implements ScheduleEntry {
        @Override
        public String toString() {
            return "approve-stale(" + slot + ")";
        }
    }

    /** The user rejects the slot's pending approval, if one is pending. */
    record Reject(int slot) implements ScheduleEntry {
        @Override
        public String toString() {
            return "reject(" + slot + ")";
        }
    }

    /** Time passes; the recovery loop steps every intent that falls due meanwhile (7.5 "Who waits"). */
    record Wait(Duration duration) implements ScheduleEntry {
        public Wait {
            Objects.requireNonNull(duration, "duration");
        }

        @Override
        public String toString() {
            return "wait(" + duration.toMillis() + " ms)";
        }
    }

    /** Merchant-side event, now: {@code POST /_lab/price} (A.3). */
    record SetPrice(String itemId, long priceMinor) implements ScheduleEntry {
        public SetPrice {
            Objects.requireNonNull(itemId, "itemId");
        }

        @Override
        public String toString() {
            return "price(" + itemId + " = " + priceMinor + ")";
        }
    }

    /** Merchant-side event, now: {@code POST /_lab/stock} (A.3). */
    record SetStock(String itemId, int stock) implements ScheduleEntry {
        public SetStock {
            Objects.requireNonNull(itemId, "itemId");
        }

        @Override
        public String toString() {
            return "stock(" + itemId + " = " + stock + ")";
        }
    }

    /** A per-call fault, or a merchant-side change made just before that call is processed (S5, S6, S16). */
    record CallFault(MerchantCall call, int nth, Fault fault) implements ScheduleEntry {
        public CallFault {
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(fault, "fault");
        }

        @Override
        public String toString() {
            return "fault(" + call + " #" + nth + ": " + fault + ")";
        }
    }

    /**
     * A kill -9 of the current worker before or after its next call of this kind (7.3 crash points, S9). A fresh
     * orchestrator with a new worker id then takes over the same repositories.
     */
    record Crash(MerchantCall call, boolean afterCall) implements ScheduleEntry {
        public Crash {
            Objects.requireNonNull(call, "call");
        }

        @Override
        public String toString() {
            return "crash(" + (afterCall ? "after " : "before ") + call + ")";
        }
    }
}
