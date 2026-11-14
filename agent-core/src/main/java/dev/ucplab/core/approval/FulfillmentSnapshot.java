package dev.ucplab.core.approval;

import java.util.Objects;
import java.util.Optional;

/**
 * The shipping part of what the user approves (9.2, H1 D4): the chosen option's id, its label, its price, and the
 * destination's country. The street address is never here: it is not in the snapshot, the card or any log.
 */
public record FulfillmentSnapshot(String optionId, String label, long priceMinor, Optional<String> destinationCountry) {

    public FulfillmentSnapshot {
        Objects.requireNonNull(optionId, "optionId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(destinationCountry, "destinationCountry");
        if (priceMinor < 0) {
            throw new IllegalArgumentException("priceMinor must not be negative: " + priceMinor);
        }
    }
}
