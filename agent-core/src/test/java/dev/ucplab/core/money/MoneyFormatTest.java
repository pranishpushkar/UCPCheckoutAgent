package dev.ucplab.core.money;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 24.3 [CONTRACT] money format, as the service writes it for the agent's texts and tool results (6.2, 6.4). */
class MoneyFormatTest {

    // Covers: 24.3 money format (the live check's amounts: the rupee symbol and Indian grouping for INR, threes for USD)
    @Test
    void theLiveChecksAmounts() {
        assertThat(MoneyFormat.format(1_200_000, "INR")).isEqualTo("₹12,000.00");
        assertThat(MoneyFormat.format(800_000, "INR")).isEqualTo("₹8,000.00");
        assertThat(MoneyFormat.format(22_498, "USD")).isEqualTo("$224.98");
    }

    // Covers: 24.3 money format (Indian grouping: the last three digits, then pairs)
    @Test
    void inr_usesIndianGrouping() {
        assertThat(MoneyFormat.format(0, "INR")).isEqualTo("₹0.00");
        assertThat(MoneyFormat.format(5, "INR")).isEqualTo("₹0.05");
        assertThat(MoneyFormat.format(99_900, "INR")).isEqualTo("₹999.00");
        assertThat(MoneyFormat.format(100_000, "INR")).isEqualTo("₹1,000.00");
        assertThat(MoneyFormat.format(10_000_000, "INR")).isEqualTo("₹1,00,000.00");
        assertThat(MoneyFormat.format(15_000_000, "INR")).isEqualTo("₹1,50,000.00");
        assertThat(MoneyFormat.format(1_500_000_000, "INR")).isEqualTo("₹1,50,00,000.00");
        assertThat(MoneyFormat.format(123_456_789_012L, "INR")).isEqualTo("₹1,23,45,67,890.12");
    }

    // Covers: 24.3 money format (every other currency groups by threes, with its own symbol and minor digits; a symbol
    // that ends in a letter is followed by a space; an unknown code is its code, a space and two digits)
    @Test
    void otherCurrencies_groupByThrees_withTheirOwnSymbolAndDigits() {
        assertThat(MoneyFormat.format(150_000_000, "USD")).isEqualTo("$1,500,000.00");
        assertThat(MoneyFormat.format(99, "USD")).isEqualTo("$0.99");
        assertThat(MoneyFormat.format(123_456, "EUR")).isEqualTo("€1,234.56");
        assertThat(MoneyFormat.format(4_000, "GBP")).isEqualTo("£40.00");
        assertThat(MoneyFormat.format(1_500, "JPY")).isEqualTo("¥1,500");
        assertThat(MoneyFormat.format(1_234_567, "BHD")).isEqualTo("BHD 1,234.567");
        assertThat(MoneyFormat.format(100, "CHF")).isEqualTo("CHF 1.00");
        assertThat(MoneyFormat.format(2_500, "CAD")).isEqualTo("CA$25.00");
        assertThat(MoneyFormat.format(1_234, "ABC")).isEqualTo("ABC 12.34");
    }

    // Covers: 24.3 money format (never floating point: the largest and smallest amounts are exact, and a negative amount
    // has its minus before the symbol)
    @Test
    void extremes_areExact_andANegativeAmountHasItsMinusFirst() {
        assertThat(MoneyFormat.format(Long.MAX_VALUE, "USD")).isEqualTo("$92,233,720,368,547,758.07");
        assertThat(MoneyFormat.format(Long.MIN_VALUE, "USD")).isEqualTo("-$92,233,720,368,547,758.08");
        assertThat(MoneyFormat.format(9_007_199_254_740_993L, "INR")).isEqualTo("₹9,00,71,99,25,47,409.93");
        assertThat(MoneyFormat.format(-22_498, "USD")).isEqualTo("-$224.98");
    }
}
