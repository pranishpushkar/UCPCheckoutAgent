package dev.ucplab.core.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CatalogTypesTest {

    private static final CatalogVariant VARIANT = new CatalogVariant("item_1", "Mug", new Money(1200, "USD"),
            new Availability(true, Optional.of("in_stock")));

    // Covers: FR1 (prices are integer minor units with an ISO 4217 currency)
    @Test
    void money_rejectsNegativeAmountsAndBadCurrencies() {
        assertThatThrownBy(() -> new Money(-1, "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(1, "usd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(1, "US")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Money(0, "EUR").minor()).isZero();
    }

    // Covers: FR1 (a merchant that does not say a variant is available is treated as not available)
    @Test
    void availability_unstated_isNotAvailable() {
        assertThat(Availability.unstated().available()).isFalse();
        assertThat(Availability.unstated().status()).isEmpty();
    }

    // Covers: FR1 (a product has at least one variant; the lists are defensive copies)
    @Test
    void product_needsAVariant_andCopiesItsList() {
        assertThatThrownBy(() -> new CatalogProduct("p", "Mug", List.of())).isInstanceOf(IllegalArgumentException.class);
        List<CatalogVariant> variants = new ArrayList<>(List.of(VARIANT));
        CatalogProduct p = new CatalogProduct("p", "Mug", variants);
        variants.clear();
        assertThat(p.variants()).containsExactly(VARIANT);
    }

    // Covers: FR1, 6.2 (max_results is 1 to 10)
    @Test
    void search_limitIsOneToTen() {
        assertThatThrownBy(() -> new CatalogSearch("mug", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogSearch("mug", 11)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CatalogSearch("", 10).limit()).isEqualTo(10);
    }
}
