package dev.ucplab.core.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A merchant's continue_url carries an access key: it prints as its scheme and host only (Appendix B, I8; H5 C1). */
class ContinueUrlTest {

    private static final String CANARY = "CANARY-key-3c7d1e";
    private static final String URL = "https://example-dev.myshopify.com/cart/c/hWN1x2?key=" + CANARY;

    // Covers: Appendix B (ContinueUrl), I8, H5 C1 (toString is written by hand: the scheme and host only)
    @Test
    void toString_showsTheSchemeAndHostOnly() {
        ContinueUrl url = ContinueUrl.parse(URL).orElseThrow();

        assertThat(url.toString()).isEqualTo("https://example-dev.myshopify.com");
        assertThat(String.valueOf(Optional.of(url))).doesNotContain(CANARY).doesNotContain("/cart/c/");
        assertThat(url.value()).isEqualTo(URI.create(URL));
    }

    // Covers: I8, H5 C1 (a userinfo part, a port, a path-only or an opaque URL never prints more than the host)
    @Test
    void toString_neverShowsUserinfoPathOrQuery() {
        assertThat(ContinueUrl.parse("https://" + CANARY + "@evil.example:8443/x?key=" + CANARY).orElseThrow())
                .hasToString("https://evil.example");
        assertThat(ContinueUrl.parse("/cart/c/x?key=" + CANARY).orElseThrow()).hasToString("[redacted]");
        assertThat(ContinueUrl.parse("mailto:" + CANARY + "@example.com").orElseThrow()).hasToString("[redacted]");
    }

    // Covers: I8, H5 C1 (a malformed keyed URL is refused with the fixed reason: URISyntaxException's message holds its
    // input and is never surfaced, and nothing is thrown)
    @Test
    void malformedKeyedUrl_isEmpty_neverAnExceptionThatEchoesIt() {
        for (String bad : List.of(
                "https://example-dev.myshopify.com/cart/c/x y?key=" + CANARY,
                "https://example-dev.myshopify.com/cart/c/x?key=" + CANARY + "%zz",
                "https://[" + CANARY + "/?key=" + CANARY,
                "ht tp://example-dev.myshopify.com/?key=" + CANARY,
                "")) {
            assertThatCode(() -> assertThat(ContinueUrl.parse(bad)).isEmpty()).doesNotThrowAnyException();
        }
        assertThat(ContinueUrl.INVALID).isEqualTo("continue_url_invalid").doesNotContain(CANARY);
    }
}
