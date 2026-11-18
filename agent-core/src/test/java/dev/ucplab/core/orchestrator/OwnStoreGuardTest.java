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
}
