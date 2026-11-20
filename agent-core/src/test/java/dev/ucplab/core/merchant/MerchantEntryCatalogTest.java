package dev.ucplab.core.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A catalog source's registry entry (19 "Catalog sources", 26.7, I12). */
class MerchantEntryCatalogTest {

    private static final MerchantId ID = new MerchantId("shopify-catalog");
    private static final URI URL = URI.create("https://catalog.shopify.com/api/ucp/mcp");

    private static MerchantEntry entry(Transport transport, Completion completion, boolean owned,
                                       Optional<String> shopDomain) {
        return new MerchantEntry(ID, URL, "Shopify catalog", MerchantStatus.ACTIVE, transport, completion, owned,
                shopDomain);
    }

    // Covers: 19, 10 (a catalog entry: completion link, not owned, no shop_domain; its fingerprint as 19 states it)
    @Test
    void aCatalogEntry_hasTheFingerprintOf19() {
        assertThat(entry(Transport.CATALOG, Completion.LINK, false, Optional.empty()).fingerprint())
                .isEqualTo("{\"completion\":\"link\",\"owned\":false,\"transport\":\"catalog\"}");
    }

    // Covers: 19, I12 (transport catalog goes with completion link only, and completion link with transport catalog
    // only; a catalog entry is never owned and has no shop_domain)
    @Test
    void aCatalogEntry_isRefused_unlessItIsALinkSourceOfNoStore() {
        for (Completion other : new Completion[] {Completion.AGENT, Completion.HANDOFF}) {
            assertThatThrownBy(() -> entry(Transport.CATALOG, other, false, Optional.empty())).as(other.name())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> entry(Transport.REST, Completion.LINK, false, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry(Transport.MCP, Completion.LINK, false, Optional.of("shop.example")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry(Transport.CATALOG, Completion.LINK, true, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry(Transport.CATALOG, Completion.LINK, false, Optional.of("shop.example")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
