package dev.ucplab.ucp;

import dev.ucplab.core.json.LenientJson;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads a {@code /.well-known/ucp} profile (2.1): {@code ucp.version}, the endpoint of a transport at
 * {@code ucp.services["dev.ucp.shopping"][transport=rest|mcp].endpoint} (8.5), the keys of {@code ucp.capabilities}, the ids
 * in {@code ucp.payment_handlers} and the keys of {@code ucp.supported_versions} (26.2). Unknown fields are ignored (8.4).
 */
final class ProfileParser {

    static final String SHOPPING_SERVICE = "dev.ucp.shopping";

    private ProfileParser() {
    }

    /**
     * @throws IllegalArgumentException with a reason that names a field and never a value, if a field the client needs is
     *     missing or has the wrong type
     */
    static MerchantProfile parse(MerchantId merchant, byte[] body) {
        return parse(merchant, body, "rest");
    }

    /** As {@link #parse(MerchantId, byte[])}, with the endpoint of the given {@code transport} ({@code rest} or {@code mcp}). */
    static MerchantProfile parse(MerchantId merchant, byte[] body, String transport) {
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(transport, "transport");
        Map<String, Object> root;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            root = LenientJson.readObject(text);
        } catch (CharacterCodingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("unparseable profile");
        }
        if (!(root.get("ucp") instanceof Map<?, ?> ucp)) {
            throw new IllegalArgumentException("ucp missing");
        }
        if (!(ucp.get("version") instanceof String version) || version.isBlank()) {
            throw new IllegalArgumentException("ucp.version missing");
        }
        return new MerchantProfile(merchant, version, endpoint(ucp, transport), capabilities(ucp), handlers(ucp),
                supportedVersions(ucp));
    }

    private static URI endpoint(Map<?, ?> ucp, String transport) {
        if (ucp.get("services") instanceof Map<?, ?> services && services.get(SHOPPING_SERVICE) instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> svc && transport.equals(svc.get("transport"))
                        && svc.get("endpoint") instanceof String endpoint && !endpoint.isBlank()) {
                    try {
                        URI uri = new URI(endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
                        if (uri.getHost() == null || !(("http".equals(uri.getScheme())) || "https".equals(uri.getScheme()))) {
                            throw new IllegalArgumentException("endpoint not an http(s) URL");
                        }
                        return uri;
                    } catch (URISyntaxException e) {
                        throw new IllegalArgumentException("endpoint not a URL");
                    }
                }
            }
        }
        throw new IllegalArgumentException(transport + " endpoint missing");
    }

    private static Set<String> capabilities(Map<?, ?> ucp) {
        Set<String> out = new LinkedHashSet<>();
        if (ucp.get("capabilities") instanceof Map<?, ?> caps) {
            caps.keySet().forEach(k -> out.add(String.valueOf(k)));
        }
        return out;
    }

    /** The keys of {@code ucp.supported_versions} (26.2): each a version the merchant also speaks, at its own URL. */
    private static Set<String> supportedVersions(Map<?, ?> ucp) {
        Set<String> out = new LinkedHashSet<>();
        if (ucp.get("supported_versions") instanceof Map<?, ?> versions) {
            for (Object k : versions.keySet()) {
                String v = String.valueOf(k);
                if (!v.isBlank()) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static Set<String> handlers(Map<?, ?> ucp) {
        Set<String> out = new LinkedHashSet<>();
        if (ucp.get("payment_handlers") instanceof Map<?, ?> byNamespace) {
            for (Object list : byNamespace.values()) {
                if (list instanceof List<?> l) {
                    for (Object h : l) {
                        if (h instanceof Map<?, ?> m && m.get("id") instanceof String id && !id.isBlank()) {
                            out.add(id);
                        }
                    }
                }
            }
        }
        return out;
    }
}
