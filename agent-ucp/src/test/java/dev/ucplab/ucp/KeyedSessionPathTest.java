package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A keyed checkout id goes into a REST path only as one percent-encoded segment, so building the URI can never fail with
 * a {@code URISyntaxException}, whose message would hold the key (I8; H5 C1).
 */
class KeyedSessionPathTest {

    private static final String CANARY = "CANARY-key-9a4b2c";

    // Covers: I8, 8.4, H5 C1
    @Test
    void keyedOrMalformedSessionId_alwaysBuildsAUri_withTheQueryEncoded() {
        for (String id : List.of(
                "gid://shopify/Checkout/hWN1x2?key=" + CANARY,
                "gid://shopify/Checkout/x y?key=" + CANARY + "%zz",
                "[" + CANARY + "?key=" + CANARY + "#frag",
                "../" + CANARY + "?cart=" + CANARY,
                "\n" + CANARY + "é")) {
            assertThatCode(() -> {
                URI uri = URI.create("https://example-dev.myshopify.com/api/ucp/checkout-sessions/"
                        + UcpHttpGateway.segment(id));
                assertThat(uri.getRawQuery()).isNull();
                assertThat(uri.getRawFragment()).isNull();
                assertThat(uri.getRawPath()).doesNotContain("?").doesNotContain("/" + CANARY);
            }).doesNotThrowAnyException();
        }
    }
}
