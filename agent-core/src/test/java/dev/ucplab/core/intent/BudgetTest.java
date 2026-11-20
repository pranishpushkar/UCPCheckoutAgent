package dev.ucplab.core.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BudgetTest {

    // Covers: P9, 23.1 (a budget is at least one minor unit)
    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void budget_refusesAnAmountBelowOneMinorUnit(long amount) {
        assertThatThrownBy(() -> new Budget(amount, "USD")).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: P9, 23.1 (a currency is three upper-case letters)
    @ParameterizedTest
    @ValueSource(strings = {"", "usd", "US", "USDX", "U$D", "US1"})
    void budget_refusesACurrencyThatIsNotThreeCapitals(String currency) {
        assertThatThrownBy(() -> new Budget(500, currency)).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 6.4, 24.3 (the budget is shown in its currency's own minor digits, from the JDK's ISO 4217 table: two for
    // USD and INR, none for JPY; "$" for USD and the code otherwise; a code the table does not know takes two)
    @Test
    void display_usesTheCurrencysOwnMinorDigits() {
        assertThat(new Budget(50_000, "USD").display()).isEqualTo("$500.00");
        assertThat(new Budget(4_270_000, "INR").display()).isEqualTo("INR 42700.00");
        assertThat(new Budget(500, "JPY").display()).isEqualTo("JPY 500");
        assertThat(new Budget(5, "EUR").display()).isEqualTo("EUR 0.05");
        assertThat(new Budget(1_234, "ABC").display()).isEqualTo("ABC 12.34");
    }

    // Covers: P9, 23.1 (one minor unit and a three-letter code make a budget)
    @Test
    void budget_acceptsOneMinorUnit_andAThreeLetterCode() {
        assertThat(new Budget(1, "USD").amountMinor()).isEqualTo(1);
        assertThat(new Budget(1_000_000_000_000L, "EUR").currency()).isEqualTo("EUR");
    }
}
