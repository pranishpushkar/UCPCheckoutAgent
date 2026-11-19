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

    // Covers: P9, 23.1 (one minor unit and a three-letter code make a budget)
    @Test
    void budget_acceptsOneMinorUnit_andAThreeLetterCode() {
        assertThat(new Budget(1, "USD").amountMinor()).isEqualTo(1);
        assertThat(new Budget(1_000_000_000_000L, "EUR").currency()).isEqualTo("EUR");
    }
}
