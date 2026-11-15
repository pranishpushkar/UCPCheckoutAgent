package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** H3 P8: what the client takes from a merchant's discovery profile (8.5, 19, 26.2), field by field, per mutant. */
class ProfileParserMutationTest {

    private static final MerchantId SHOP = new MerchantId("shop-1");

    private static MerchantProfile parse(String json) {
        return ProfileParser.parse(SHOP, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void refused(String json, String reason) {
        assertThatThrownBy(() -> parse(json)).as(json).isInstanceOf(IllegalArgumentException.class).hasMessage(reason);
    }

    private static String profile(String services) {
        return "{\"ucp\":{\"version\":\"2026-08-25\",\"services\":" + services + "}}";
    }

    private static String shopping(String list) {
        return profile("{\"dev.ucp.shopping\":" + list + "}");
    }

    private static String rest(String endpoint) {
        return "{\"transport\":\"rest\",\"endpoint\":\"" + endpoint + "\"}";
    }

    // Covers: 8.5. Kills ProfileParser.parse:44 and :47 (the profile is an object with a `ucp` object and a non-blank version)
    @Test
    void aProfile_needsItsUcpObjectAndVersion() {
        refused("not json", "unparseable profile");
        assertThatThrownBy(() -> ProfileParser.parse(SHOP, new byte[] {(byte) 0xC3, 0x28}))
                .hasMessage("unparseable profile");
        refused("{}", "ucp missing");
        refused("{\"ucp\":\"x\"}", "ucp missing");
        refused("{\"ucp\":{}}", "ucp.version missing");
        refused("{\"ucp\":{\"version\":\" \"}}", "ucp.version missing");
        refused("{\"ucp\":{\"version\":5}}", "ucp.version missing");
        refused("{\"ucp\":{\"version\":\"2026-08-25\"}}", "rest endpoint missing");
    }

    // Covers: 8.5. Kills ProfileParser.endpoint:54, :56, :57 (which entry of `dev.ucp.shopping` is the endpoint: the first
    // that is a REST service with a non-blank endpoint)
    @Test
    void theEndpoint_isTheFirstRestServiceWithAnEndpoint() {
        refused(profile("\"x\""), "rest endpoint missing");
        refused(profile("{\"dev.ucp.other\":[" + rest("http://h:1/v") + "]}"), "rest endpoint missing");
        refused(shopping("{}"), "rest endpoint missing");
        refused(shopping("[]"), "rest endpoint missing");
        refused(shopping("[\"x\"]"), "rest endpoint missing");
        refused(shopping("[{\"transport\":\"mcp\",\"endpoint\":\"http://h:1/v\"}]"), "rest endpoint missing");
        refused(shopping("[{\"endpoint\":\"http://h:1/v\"}]"), "rest endpoint missing");
        refused(shopping("[{\"transport\":\"rest\"}]"), "rest endpoint missing");
        refused(shopping("[{\"transport\":\"rest\",\"endpoint\":\" \"}]"), "rest endpoint missing");
        refused(shopping("[{\"transport\":\"rest\",\"endpoint\":5}]"), "rest endpoint missing");

        assertThat(parse(shopping("[\"x\",{\"transport\":\"mcp\",\"endpoint\":\"http://wrong:1/v\"},{\"transport\":\"rest\",\"endpoint\":\" \"},"
                + "{\"transport\":\"rest\",\"endpoint\":5}," + rest("http://right:2/v") + "," + rest("http://later:3/v") + "]")).checkoutEndpoint())
                .isEqualTo(URI.create("http://right:2/v"));
    }

    // Covers: 8.5. Kills ProfileParser.endpoint:59 (NO_COVERAGE Math, RemoveConditional) and :60 (a trailing slash is dropped; the
    // endpoint is an http or https URL with a host, else the profile is refused; the first REST service decides, even a bad one)
    @Test
    void theEndpoint_isAnHttpUrl_withoutATrailingSlash() {
        assertThat(parse(shopping("[" + rest("http://h:1/ucp/v1/") + "]")).checkoutEndpoint()).isEqualTo(URI.create("http://h:1/ucp/v1"));
        assertThat(parse(shopping("[" + rest("https://h/ucp/v1") + "]")).checkoutEndpoint()).isEqualTo(URI.create("https://h/ucp/v1"));
        assertThat(parse(shopping("[" + rest("http://h/") + "]")).checkoutEndpoint()).isEqualTo(URI.create("http://h"));

        refused(shopping("[" + rest("ftp://h/v") + "]"), "endpoint not an http(s) URL");
        refused(shopping("[" + rest("/ucp/v1") + "]"), "endpoint not an http(s) URL");
        refused(shopping("[" + rest("/") + "]"), "endpoint not an http(s) URL");
        refused(shopping("[" + rest("h:1/v") + "]"), "endpoint not an http(s) URL");
        refused(shopping("[" + rest("http:/ucp/v1") + "]"), "endpoint not an http(s) URL");
        refused(shopping("[" + rest("https:///ucp/v1") + "]"), "endpoint not an http(s) URL");
        refused(shopping("[" + rest("http://bad host/v") + "]"), "endpoint not a URL");
        refused(shopping("[" + rest("http://bad/v") + "," + "{\"transport\":\"rest\",\"endpoint\":\"ftp://x/v\"}]").replace("http://bad/v", "ftp://first/v"),
                "endpoint not an http(s) URL");
    }

    // Covers: 8.5, 19. Kills ProfileParser.capabilities:75 (the capabilities are the keys of an object; absent or another type
    // is none)
    @Test
    void theCapabilities_areTheKeysOfTheObject() {
        String endpoint = "\"services\":{\"dev.ucp.shopping\":[" + rest("http://h:1/v") + "]}";
        assertThat(parse("{\"ucp\":{\"version\":\"v\"," + endpoint + "}}").capabilities()).isEmpty();
        assertThat(parse("{\"ucp\":{\"version\":\"v\"," + endpoint + ",\"capabilities\":[\"a\"]}}").capabilities()).isEmpty();
        assertThat(parse("{\"ucp\":{\"version\":\"v\"," + endpoint + ",\"capabilities\":{\"dev.ucp.shopping.checkout\":[],\"b\":{}}}}").capabilities())
                .containsExactlyInAnyOrder("dev.ucp.shopping.checkout", "b");
    }

    // Covers: 19. Kills ProfileParser.handlers:83, :85, :87 (the payment handler ids: every non-blank string `id` of every entry
    // of every namespace; anything else is skipped)
    @Test
    void thePaymentHandlers_areTheIdsOfEveryEntry() {
        String endpoint = "\"services\":{\"dev.ucp.shopping\":[" + rest("http://h:1/v") + "]}";
        assertThat(parse("{\"ucp\":{\"version\":\"v\"," + endpoint + "}}").paymentHandlers()).isEmpty();
        assertThat(parse("{\"ucp\":{\"version\":\"v\"," + endpoint + ",\"payment_handlers\":[\"x\"]}}").paymentHandlers()).isEmpty();
        assertThat(parse("{\"ucp\":{\"version\":\"v\"," + endpoint + ",\"payment_handlers\":{\"ns1\":\"x\",\"ns2\":{},\"ns3\":[]}}}").paymentHandlers())
                .isEmpty();
        assertThat(parse("{\"ucp\":{\"version\":\"v\"," + endpoint + ",\"payment_handlers\":{\"ns1\":[{\"id\":\"h1\"},\"str\",{\"id\":\" \"},"
                + "{\"id\":5},{\"name\":\"n\"},{\"id\":\"h2\"}],\"ns2\":{\"id\":\"ignored\"},\"ns3\":[{\"id\":\"h3\"}]}}}").paymentHandlers())
                .containsExactlyInAnyOrder("h1", "h2", "h3");
        MerchantProfile p = parse("{\"ucp\":{\"version\":\"2026-08-25\"," + endpoint + "}}");
        assertThat(p.merchant()).isEqualTo(SHOP);
        assertThat(p.ucpVersion()).isEqualTo("2026-08-25");
    }
}
