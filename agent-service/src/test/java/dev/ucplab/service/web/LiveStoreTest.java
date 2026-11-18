package dev.ucplab.service.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LiveStoreTest {

    // Covers: 23.2, 23.3, H5 (a live store is owned, with a shop_domain that is not a loopback host; anything else is
    // not, and has no domain to show)
    @ParameterizedTest(name = "owned={0} shop_domain={1}")
    @CsvSource(delimiter = '|', nullValues = "none", value = {
        "true  | example-dev.myshopify.com | example-dev.myshopify.com",
        "false | example-dev.myshopify.com | none",
        "true  | none                      | none",
        "true  | 127.0.0.1                 | none",
        "true  | localhost                 | none",
        "true  | LOCALHOST                 | none",
        "true  | [::1]                     | none"})
    void aLiveStore_isOwned_withAShopDomainThatIsNotLoopback(boolean owned, String shopDomain, String expected) {
        assertThat(LiveStore.domain(owned, Optional.ofNullable(shopDomain))).isEqualTo(Optional.ofNullable(expected));
    }
}
