package dev.ucplab.ucp;

import dev.ucplab.core.merchant.CallOutcome;

/**
 * Admission control for one host:port (8.3): the circuit breaker implements it. {@link RetryExecutor} asks before each
 * attempt and reports what happened after it.
 */
public interface Gate {

    /** A gate that always admits and ignores outcomes. */
    Gate NONE = new Gate() {
        @Override
        public boolean tryAcquire() {
            return true;
        }

        @Override
        public void record(CallOutcome<?> outcome) {
            // nothing to record
        }
    };

    /** Whether this attempt may be sent. False means the call is rejected without sending (NotSent). */
    boolean tryAcquire();

    /** The outcome of an attempt that {@link #tryAcquire} admitted. */
    void record(CallOutcome<?> outcome);
}
