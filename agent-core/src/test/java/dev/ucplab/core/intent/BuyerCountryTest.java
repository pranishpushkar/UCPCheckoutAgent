package dev.ucplab.core.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BuyerCountryTest {

    // Covers: 23.1, 26.7 (a buyer country's currency is the JDK's ISO 4217 currency for it, and its name is the
    // English one: a table, not FX)
    @Test
    void aCountry_hasItsCurrency_andItsEnglishName() {
        assertThat(BuyerCountry.US.currency()).isEqualTo("USD");
        assertThat(BuyerCountry.US.name()).isEqualTo("United States");
        assertThat(new BuyerCountry("IN").currency()).isEqualTo("INR");
        assertThat(new BuyerCountry("IN").name()).isEqualTo("India");
        assertThat(new BuyerCountry("GB").currency()).isEqualTo("GBP");
        assertThat(new BuyerCountry("JP").currency()).isEqualTo("JPY");
        assertThat(new BuyerCountry("DE").currency()).isEqualTo("EUR");
    }

    // Covers: 23.1 (only two upper-case letters naming an ISO 3166-1 country the JDK knows; a country with no single
    // currency, such as Antarctica, is refused too)
    @ParameterizedTest
    @ValueSource(strings = {"", "us", "In", "IND", "U", "XX", "ZZ", "U1", "AQ"})
    void anythingElse_isRefused(String code) {
        assertThatThrownBy(() -> new BuyerCountry(code)).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 23.1 (GET /api/countries: every country with one currency, sorted by English name, US and India among them,
    // Antarctica not)
    @Test
    void all_isEveryCountryWithOneCurrency_sortedByName() {
        List<BuyerCountry> all = BuyerCountry.all();

        assertThat(all).hasSizeGreaterThan(200).contains(BuyerCountry.US, new BuyerCountry("IN"))
                .doesNotHaveDuplicates();
        assertThat(all).extracting(BuyerCountry::code).doesNotContain("AQ");
        assertThat(all).extracting(BuyerCountry::name).isSorted();
    }
}
