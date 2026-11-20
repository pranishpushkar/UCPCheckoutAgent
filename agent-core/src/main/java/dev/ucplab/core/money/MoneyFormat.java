package dev.ucplab.core.money;

import java.util.Currency;
import java.util.Locale;

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
        int digits = minorDigits(currency);
        // Long.toString of the magnitude as text, so Long.MIN_VALUE needs no negation.
        String raw = Long.toString(minor);
        boolean negative = raw.startsWith("-");
        String magnitude = negative ? raw.substring(1) : raw;
        if (magnitude.length() <= digits) {
            magnitude = "0".repeat(digits + 1 - magnitude.length()) + magnitude;
        }
        String whole = magnitude.substring(0, magnitude.length() - digits);
        String fraction = digits == 0 ? "" : "." + magnitude.substring(magnitude.length() - digits);
        return (negative ? "-" : "") + symbol(currency) + grouped(whole, currency) + fraction;
    }

    /** The currency's English symbol; one that ends in a letter (CHF, an unknown code) is followed by a space. */
    private static String symbol(String currency) {
        String symbol;
        try {
            symbol = Currency.getInstance(currency).getSymbol(Locale.ENGLISH);
        } catch (IllegalArgumentException | NullPointerException e) {
            symbol = currency;
        }
        return Character.isLetter(symbol.charAt(symbol.length() - 1)) ? symbol + " " : symbol;
    }

    /** Indian grouping for INR (the last three digits, then pairs), threes for every other currency. */
    private static String grouped(String whole, String currency) {
        if (whole.length() <= 3) {
            return whole;
        }
        String last = whole.substring(whole.length() - 3);
        String rest = whole.substring(0, whole.length() - 3);
        int size = currency.equals("INR") ? 2 : 3;
        StringBuilder out = new StringBuilder();
        int first = rest.length() % size == 0 ? size : rest.length() % size;
        out.append(rest, 0, first);
        for (int i = first; i < rest.length(); i += size) {
            out.append(',').append(rest, i, i + size);
        }
        return out.append(',').append(last).toString();
    }

    /** The currency's minor digits from the JDK's ISO 4217 table; two for a code the table does not know. */
    public static int minorDigits(String currency) {
        try {
            int digits = Currency.getInstance(currency).getDefaultFractionDigits();
            return digits < 0 ? 2 : digits;
        } catch (IllegalArgumentException | NullPointerException e) {
            return 2;
        }
    }
}
