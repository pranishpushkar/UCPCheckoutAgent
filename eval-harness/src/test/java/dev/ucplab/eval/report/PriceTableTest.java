package dev.ucplab.eval.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PriceTable against a fixture file with invented prices only (13.3, 13.4); no real price is in the code. */
class PriceTableTest {

    static Path fixture() throws URISyntaxException {
        return Path.of(PriceTableTest.class.getResource("/prices/fixture-prices.json").toURI());
    }

    // Covers: 13.3, 13.4 (the price file is read at run time: model, date, currency, prices as string or number)
    @Test
    void read_fixture_readsEveryField() throws Exception {
        PriceTable p = PriceTable.read(fixture());

        assertThat(p.model()).isEqualTo("fixture-model-not-real");
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(p.currency()).isEqualTo("USD");
        assertThat(p.inputPerMillionTokens()).isEqualByComparingTo("1.00");
        assertThat(p.outputPerMillionTokens()).isEqualByComparingTo("10");
    }

    // Covers: 13.3 (cost = input x input price + output x output price, per million tokens, exact)
    @Test
    void cost_fromTokens_isExact() throws Exception {
        PriceTable p = PriceTable.read(fixture());

        assertThat(p.cost(20_000, 2_000)).isEqualByComparingTo(new BigDecimal("0.04"));
        assertThat(p.cost(1, 0)).isEqualByComparingTo(new BigDecimal("0.000001"));
        assertThat(p.cost(0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThatThrownBy(() -> p.cost(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 13.3 (a malformed price file is refused, never half read)
    @Test
    void read_invalidFiles_areRefused(@TempDir Path dir) throws Exception {
        String ok = Files.readString(fixture());
        for (String bad : new String[] {
                ok.replace("\"USD\"", "\"usd\""),
                ok.replace("\"1.00\"", "\"one\""),
                ok.replace("\"1.00\"", "\"-1\""),
                ok.replace("\"source\"", "\"extra\": 1, \"source\""),
                ok.replace("\"date\": \"2026-01-01\",", "")}) {
            Path f = Files.writeString(dir.resolve("p.json"), bad);
            assertThatThrownBy(() -> PriceTable.read(f)).isInstanceOf(RuntimeException.class);
        }
    }
}
