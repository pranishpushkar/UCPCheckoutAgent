package dev.ucplab.core.merchant;

import java.util.Objects;

/** An amount in the currency's minor units (2500 USD means 25.00), as UCP sends it (2.1). Never a decimal. */
public record Money(long minor, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (minor < 0) {
            throw new IllegalArgumentException("minor units must not be negative");
        }
        if (currency.length() != 3 || !currency.chars().allMatch(c -> c >= 'A' && c <= 'Z')) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code such as USD");
        }
    }
}
