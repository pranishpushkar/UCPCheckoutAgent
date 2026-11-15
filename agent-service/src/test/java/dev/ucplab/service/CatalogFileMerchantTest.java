package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.merchant.LabMerchant;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Covers: DESIGN 19 (a test merchant can serve its own catalog, as C7's second merchant will). */
class CatalogFileMerchantTest {

    @Test
    void labMerchant_startedWithACatalogFile_servesItsPrices() throws Exception {
        try (LabMerchant b = LabMerchant.start(Path.of("..", "dev", "catalogs", "b.json"))) {
            assertThat(b.price("b_mug_01")).isEqualTo(1100L);
            b.setPrice("b_mug_01", 5);
            assertThat(b.price("b_mug_01")).isEqualTo(5L);
        }
    }
}
