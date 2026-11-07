package dev.ucplab.core.merchant;

import java.util.Locale;
import java.util.Objects;

/** Status of a merchant checkout session (Appendix B). */
public enum CheckoutStatus {
    INCOMPLETE, READY_FOR_COMPLETE, COMPLETE_IN_PROGRESS, COMPLETED, CANCELED;

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
