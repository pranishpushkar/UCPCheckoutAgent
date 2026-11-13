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

    // Covers: 13.3, 13.4, R4 (the live model's price file: gemini-3.8-flash, Paid Tier, Standard, as read on
    // https://aistudio.google.com/docs/pricing on 2026-11-13: input $0.75 and "Output price (including thinking tokens)"
    // $3.75 per 1M tokens through 2026-12-31. The page lists $1.50 and $7.50 from 2027-01-01; a run after that needs a
    // new file. Thinking tokens are not priced separately.)
    @Test
    void read_geminiPriceFile_pinnedValues() throws Exception {
        PriceTable p = PriceTable.read(Path.of(PriceTableTest.class
                .getResource("/dev/ucplab/eval/prices/gemini-3.8-flash.json").toURI()));

        assertThat(p.model()).isEqualTo("gemini-3.8-flash");
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(p.currency()).isEqualTo("USD");
        assertThat(p.inputPerMillionTokens()).isEqualByComparingTo("0.75");
        assertThat(p.outputPerMillionTokens()).isEqualByComparingTo("3.75");
        assertThat(p.source()).isEqualTo("https://aistudio.google.com/docs/pricing");
        // 20k input and 2k output tokens, the plan's ceiling for one run: 0.015 + 0.0075.
        assertThat(p.cost(20_000, 2_000)).isEqualByComparingTo(new BigDecimal("0.0225"));
    }

    // Covers: R4 (Stage 5b: the gemini-3.6-flash price file, read 2026-11-13 from its own section of
    // https://aistudio.google.com/docs/pricing, Paid Tier, Standard tab: input $0.75 and "Output price (including
    // thinking tokens)" $3.75 per 1M tokens through 2026-12-31; $1.50 and $7.50 from 2027-01-01. The same rates as
    // gemini-3.8-flash.)
    @Test
    void read_gemini36PriceFile_pinnedValues() throws Exception {
        PriceTable p = PriceTable.read(Path.of(PriceTableTest.class
                .getResource("/dev/ucplab/eval/prices/gemini-3.6-flash.json").toURI()));

        assertThat(p.model()).isEqualTo("gemini-3.6-flash");
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(p.currency()).isEqualTo("USD");
        assertThat(p.inputPerMillionTokens()).isEqualByComparingTo("0.75");
        assertThat(p.outputPerMillionTokens()).isEqualByComparingTo("3.75");
        assertThat(p.source()).isEqualTo("https://aistudio.google.com/docs/pricing");
    }
}
