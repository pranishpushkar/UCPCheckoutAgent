package dev.ucplab.core.money;

/**
 * 24.3 [CONTRACT] money format: an amount as text, from its minor units by string arithmetic on the integer, never
 * through floating point. The currency's own minor digits and its English symbol (CLDR {@code en}, from the JDK), then
 * the whole part grouped (Indian grouping for INR, threes otherwise) and the fraction after a point.
 */
public final class MoneyFormat {

    private MoneyFormat() {
    }

    /** {@code minor} of {@code currency} as text: {@code ₹12,000.00} for 1200000 INR. */
    public static String format(long minor, String currency) {
        return ""; // Skeleton (red).
    }
}
