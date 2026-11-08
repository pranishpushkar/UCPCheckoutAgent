package dev.ucplab.core.merchant.fake;

import java.util.Objects;
import java.util.Optional;

/**
 * Applies {@code fault} to calls of {@code call}: only to the {@code nth} one (1-based, counted per call type,
 * including calls that never reached the merchant), or to every one when {@code nth} is empty.
 */
public record FaultRule(MerchantCall call, Optional<Integer> nth, Fault fault) {

    public FaultRule {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(nth, "nth");
        Objects.requireNonNull(fault, "fault");
        if (nth.isPresent() && nth.get() < 1) {
            throw new IllegalArgumentException("nth is 1-based: " + nth.get());
        }
    }

    public static FaultRule nth(MerchantCall call, int n, Fault fault) {
        return new FaultRule(call, Optional.of(n), fault);
    }

    public static FaultRule every(MerchantCall call, Fault fault) {
        return new FaultRule(call, Optional.empty(), fault);
    }

    boolean matches(MerchantCall c, int n) {
        return call == c && nth.map(k -> k == n).orElse(true);
    }
}
