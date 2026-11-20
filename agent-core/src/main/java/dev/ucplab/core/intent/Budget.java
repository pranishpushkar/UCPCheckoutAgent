package dev.ucplab.core.intent;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The user's budget for a run (9.1 P9, Appendix B): {@code amountMinor} in {@code currency}'s minor units. It comes from
 * the user's own Budget field on {@code POST /api/runs} (23.1), is copied onto each intent the run proposes, and is never
 * set, raised or cleared by model output (6.2).
 */
public record Budget(long amountMinor, String currency) {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public Budget {
        Objects.requireNonNull(currency, "currency");
        if (amountMinor < 1) {
            throw new IllegalArgumentException("a budget is at least one minor unit: " + amountMinor);
        }
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("a budget's currency is three upper-case letters");
        }
    }

    /**
     * The amount as the console shows money (24.3): "$500.00" in USD, "EUR 500.00" otherwise, in the currency's own minor
     * digits from the JDK's ISO 4217 table ("JPY 500"); a code the table does not know takes two.
     */
    public String display() {
        int digits = minorDigits(currency);
        long unit = 1;
        for (int i = 0; i < digits; i++) {
            unit *= 10;
        }
        String amount = digits == 0 ? Long.toString(amountMinor)
                : amountMinor / unit + "." + String.format("%0" + digits + "d", amountMinor % unit);
        return (currency.equals("USD") ? "$" : currency + " ") + amount;
    }

    private static int minorDigits(String currency) {
        try {
            int digits = java.util.Currency.getInstance(currency).getDefaultFractionDigits();
            return digits < 0 ? 2 : digits;
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }
}
