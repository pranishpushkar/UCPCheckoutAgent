package dev.ucplab.core.merchant;

import java.util.Locale;
import java.util.Objects;

/**
 * Status of a merchant checkout session (Appendix B). {@code REQUIRES_ESCALATION} is parsed only: the buyer must take
 * over, which the agent cannot do, so a view with it is not completable (8.1) and no behaviour is attached until M6c.
 */
public enum CheckoutStatus {
    INCOMPLETE, REQUIRES_ESCALATION, READY_FOR_COMPLETE, COMPLETE_IN_PROGRESS, COMPLETED, CANCELED;

    /**
     * Parses the wire value, for example {@code ready_for_complete}.
     *
     * @throws IllegalArgumentException for an unknown value; the caller maps it to ProtocolError
     */
    public static CheckoutStatus parse(String wire) {
        Objects.requireNonNull(wire, "wire");
        for (CheckoutStatus s : values()) {
            if (s.wire().equals(wire)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown checkout status: " + wire);
    }

    /** The wire value, lower snake case. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
