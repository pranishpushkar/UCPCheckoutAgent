package dev.ucplab.core.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.ContinueUrl;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Transport;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The orchestrator's half of I12 (4.3, 26.5, H5 C2 ★): the entry, its fingerprint and a continue_url, with no I/O. */
class OwnStoreGuardTest {

    private static final MerchantId SHOP = new MerchantId("shopify-1");
    private static final String DOMAIN = "s.myshopify.com";

    private static Optional<MerchantEntry> mcp(boolean owned) {
        return Optional.of(new MerchantEntry(SHOP, URI.create("https://" + DOMAIN), "Shop", MerchantStatus.ACTIVE,
                Transport.MCP, Completion.HANDOFF, owned, Optional.of(DOMAIN)));
    }

    private static ContinueUrl url(String raw) {
        return ContinueUrl.parse(raw).orElseThrow();
    }

    // Covers: I12, 26.5 (only an entry that says owned: true allows a mutating call; no entry allows nothing)
    @Test
    void entryAllows_onlyAnOwnedEntry() {
        assertThat(OwnStoreGuard.entryAllows(mcp(true))).isTrue();
        assertThat(OwnStoreGuard.entryAllows(mcp(false))).isFalse();
        assertThat(OwnStoreGuard.entryAllows(Optional.empty())).isFalse();
    }

    // Covers: I12, 10, 19 (the entry is unchanged only when its fingerprint equals the stored one; a removed entry is a
    // change)
    @Test
    void unchanged_comparesTheFingerprint() {
        String stored = mcp(true).orElseThrow().fingerprint();

        assertThat(OwnStoreGuard.unchanged(mcp(true), stored)).isTrue();
        assertThat(OwnStoreGuard.unchanged(mcp(false), stored)).isFalse();
        assertThat(OwnStoreGuard.unchanged(Optional.empty(), stored)).isFalse();
        assertThat(OwnStoreGuard.unchanged(Optional.empty(), OwnStoreGuard.NO_ENTRY)).isFalse();
    }

    // Covers: I12 (a continue_url is accepted when it is https on shop_domain: any path and query, the host in any case,
    // no port or 443)
    @ParameterizedTest
    @ValueSource(strings = {
            "https://s.myshopify.com/cart/c/abc?key=secret",
            "HTTPS://S.MyShopify.com/cart/c/abc?key=secret",
            "https://s.myshopify.com:443/cart/c/abc",
            "https://s.myshopify.com"})
    void continueUrl_httpsOnShopDomain_isAccepted(String raw) {
        assertThat(OwnStoreGuard.continueUrlAllowed(mcp(true), url(raw))).isTrue();
    }

    // Covers: I12 (refused: http, another host, user info, a look-alike suffix or prefix, a trailing dot, a path-only or
    // opaque URL, another port)
    @ParameterizedTest
    @ValueSource(strings = {
            "http://s.myshopify.com/cart/c/abc?key=secret",
            "ftp://s.myshopify.com/cart",
            "https://other.myshopify.com/cart/c/abc",
            "https://evil@s.myshopify.com/cart/c/abc",
            "https://s.myshopify.com@evil.example/cart/c/abc",
            "https://s.myshopify.com.evil.example/cart/c/abc",
            "https://evil-s.myshopify.com/cart/c/abc",
            "https://x.s.myshopify.com/cart/c/abc",
            "https://s.myshopify.com./cart/c/abc",
            "/cart/c/abc?key=secret",
            "//s.myshopify.com/cart/c/abc",
            "https:s.myshopify.com",
            "https://s.myshopify.com:8443/cart/c/abc"})
    void continueUrl_otherwise_isRefused(String raw) {
        assertThat(OwnStoreGuard.continueUrlAllowed(mcp(true), url(raw))).isFalse();
    }

    // Covers: I12, 26.5 (a continue_url is refused, even on the right host, at an unowned entry, at an entry with no
    // shop_domain, and with no entry)
    @Test
    void continueUrl_withoutAnOwnedEntryWithAShopDomain_isRefused() {
        ContinueUrl good = url("https://s.myshopify.com/cart/c/abc");
        Optional<MerchantEntry> rest = Optional.of(new MerchantEntry(SHOP, URI.create("https://" + DOMAIN), "Shop",
                MerchantStatus.ACTIVE, Transport.REST, Completion.HANDOFF, true, Optional.empty()));

        assertThat(OwnStoreGuard.continueUrlAllowed(mcp(false), good)).isFalse();
        assertThat(OwnStoreGuard.continueUrlAllowed(rest, good)).isFalse();
        assertThat(OwnStoreGuard.continueUrlAllowed(Optional.empty(), good)).isFalse();
    }

    // ---------------------------------------------------------------- link merchants (26.7)

    private static final Optional<MerchantEntry> CATALOG = Optional.of(new MerchantEntry(SHOP,
            URI.create("https://catalog.shopify.com/api/ucp/mcp"), "Shopify catalog", MerchantStatus.ACTIVE,
            Transport.CATALOG, Completion.LINK, false, Optional.empty()));
    private static final String SELLER_DOMAIN = "shop-a.myshopify.example";
    private static final Optional<String> SELLER_SITE = Optional.of("www.shop-a.example");

    // Covers: I12, 26.7 (propose accepts a link entry without owned; an entry that is neither owned nor link, or none,
    // is refused; a link entry still allows no mutating call)
    @Test
    void proposalAllows_anOwnedEntryOrALinkEntry() {
        assertThat(OwnStoreGuard.proposalAllows(mcp(true))).isTrue();
        assertThat(OwnStoreGuard.proposalAllows(CATALOG)).isTrue();
        assertThat(OwnStoreGuard.proposalAllows(mcp(false))).isFalse();
        assertThat(OwnStoreGuard.proposalAllows(Optional.empty())).isFalse();
        assertThat(OwnStoreGuard.entryAllows(CATALOG)).as("no mutating call to a catalog").isFalse();
    }

    // Covers: I12 C3, 26.7 (a checkout_url is accepted when it is https on the host of seller.url or on seller.domain:
    // any path and query, the host in any case, no port or 443)
    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.shop-a.example/buy/1:1?_gsid=FAKE&attribution%2Fclick_id_tag=FAKETAG",
            "https://WWW.Shop-A.example/buy/1:1",
            "https://www.shop-a.example:443/buy/1:1",
            "https://shop-a.myshopify.example/buy/1:1",
            "https://SHOP-A.myshopify.example/buy/1:1"})
    void linkAllowed_httpsOnTheSellersSiteOrDomain(String raw) {
        assertThat(OwnStoreGuard.linkAllowed(SELLER_DOMAIN, SELLER_SITE, url(raw))).as(raw).isTrue();
    }

    // Covers: I12 C3, 26.7 (refused: another host, a look-alike or a subdomain, user info, another port, plain http)
    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.elsewhere.example/buy/1:1",
            "https://www.shop-a.example.elsewhere.example/buy/1:1",
            "https://pay.www.shop-a.example/buy/1:1",
            "https://shop-a.example/buy/1:1",
            "https://www.shop-a.example@elsewhere.example/buy/1:1",
            "https://user@www.shop-a.example/buy/1:1",
            "https://www.shop-a.example:8443/buy/1:1",
            "http://www.shop-a.example/buy/1:1",
            "https://checkout.shopify.com/buy/1:1"})
    void linkAllowed_refusesAnyOtherLink(String raw) {
        assertThat(ContinueUrl.parse(raw).map(u -> OwnStoreGuard.linkAllowed(SELLER_DOMAIN, SELLER_SITE, u)))
                .as(raw).isNotEqualTo(Optional.of(true));
    }

    // Covers: I12 C3, 26.7 (a seller without a site is compared with its domain only)
    @Test
    void linkAllowed_withoutASite_comparesTheDomainOnly() {
        assertThat(OwnStoreGuard.linkAllowed(SELLER_DOMAIN, Optional.empty(),
                url("https://shop-a.myshopify.example/buy/1:1"))).isTrue();
        assertThat(OwnStoreGuard.linkAllowed(SELLER_DOMAIN, Optional.empty(),
                url("https://www.shop-a.example/buy/1:1"))).isFalse();
    }
}
