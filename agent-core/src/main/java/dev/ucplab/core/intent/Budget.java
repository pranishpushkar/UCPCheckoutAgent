package dev.ucplab.core.intent;

import dev.ucplab.core.money.MoneyFormat;
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

    /** The amount in 24.3's money format, as the console shows money: "$500.00", "₹42,700.00", "¥500". */
    public String display() {
        return MoneyFormat.format(amountMinor, currency);
    }
}
