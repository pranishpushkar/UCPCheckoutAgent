package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.json.LenientJson;
import dev.ucplab.merchant.LabMerchant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Covers: DESIGN 19 and A.2 (merchants b and c serve their own catalogs, from dev/catalogs, through --catalog's loader). */
class CatalogFilesTest {

    private static final Path B = Path.of("..", "dev", "catalogs", "b.json");
    private static final Path C = Path.of("..", "dev", "catalogs", "c.json");

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(LabMerchant merchant) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(merchant.baseUrl() + "/_lab/catalog")).build(),
                HttpResponse.BodyHandlers.ofString());
        return (List<Map<String, Object>>) (List<?>) LenientJson.readObject(r.body()).get("items");
    }

    private static long field(LabMerchant merchant, String id, String key) throws Exception {
        return items(merchant).stream().filter(i -> id.equals(i.get("id"))).mapToLong(i -> ((Number) i.get(key)).longValue())
                .findFirst().orElseThrow();
    }

    @Test
    void catalogFiles_serveDistinctItems_whichResetRestores() throws Exception {
        try (LabMerchant b = LabMerchant.start(B); LabMerchant c = LabMerchant.start(C)) {
            List<Object> bIds = items(b).stream().map(i -> i.get("id")).toList();
            List<Object> cIds = items(c).stream().map(i -> i.get("id")).toList();
            assertThat(bIds).containsExactly("b_mug_01", "b_tee_02", "b_cap_03");
            assertThat(cIds).containsExactly("c_mug_01", "c_bag_02", "c_shoe_03");
            assertThat(items(b)).anySatisfy(i -> assertThat(((Number) i.get("stock")).intValue()).isEqualTo(1));

            b.setStock("b_cap_03", 0);
            b.setPrice("b_mug_01", 5);
            b.reset();
            assertThat(items(b)).extracting(i -> i.get("id")).containsExactly("b_mug_01", "b_tee_02", "b_cap_03");
            assertThat(field(b, "b_mug_01", "price")).isEqualTo(1100L);
            assertThat(field(b, "b_cap_03", "stock")).isEqualTo(1L);
        }
    }
}
