package dev.ucplab.core.intent;

import java.util.List;
import java.util.Objects;

/**
 * The run's buyer country (23.1, 26.7, Appendix B): an ISO 3166-1 alpha-2 code. Skeleton (red): no validation yet.
 */
public record BuyerCountry(String code) {

    /** The default when neither the user nor the operator names one (23.1). */
    public static final BuyerCountry US = new BuyerCountry("US");

    public BuyerCountry {
        Objects.requireNonNull(code, "code");
    }

    /** The country's ISO 4217 currency. Skeleton (red). */
    public String currency() {
        return "USD";
    }

    /** The country's English name. Skeleton (red). */
    public String name() {
        return code;
    }

    /** Every country with one currency, sorted by name. Skeleton (red). */
    public static List<BuyerCountry> all() {
        return List.of(US);
    }
}
