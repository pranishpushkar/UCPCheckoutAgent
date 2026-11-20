package dev.ucplab.core.policy;

/**
 * P2 and P3 of table 9.1 [CONTRACT] for one ISO 4217 currency, in that currency's minor units. A quote is checked
 * against the caps of its own currency only, never converted.
 *
 * @param perPurchaseMinor P2
 * @param dailyMinor P3
 */
public record CurrencyCaps(long perPurchaseMinor, long dailyMinor) {

    public CurrencyCaps {
        if (perPurchaseMinor < 1) {
            throw new IllegalArgumentException("perPurchaseMinor must be positive: " + perPurchaseMinor);
        }
        if (dailyMinor < 1) {
            throw new IllegalArgumentException("dailyMinor must be positive: " + dailyMinor);
        }
    }
}
