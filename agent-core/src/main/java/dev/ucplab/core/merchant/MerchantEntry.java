package dev.ucplab.core.merchant;

import dev.ucplab.core.json.CanonicalJson;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One merchant in the registry (section 19, Appendix B). {@code owned} is the resolved value: the operator owns the
 * store (26.5). It defaults to true only on a loopback host, the lab merchants (I12). {@code shopDomain} is the store's
 * host, which the MCP endpoint and every continue_url must match (I12); every MCP merchant has one. A catalog source
 * ({@code transport: catalog}, 26.7) is the one entry with {@code completion: link}: never owned, with no shop_domain.
 */
public record MerchantEntry(
        MerchantId id,
        URI baseUrl,
        String displayName,
        MerchantStatus status,
        Transport transport,
        Completion completion,
        boolean owned,
        Optional<String> shopDomain) {

    /** A 127.0.0.0/8 address in plain dotted form: four decimal octets, no leading zeros. */
    private static final Pattern LOOPBACK_V4 =
            Pattern.compile("127(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}");

    /**
     * The fingerprint of a lab entry (REST, completed by the agent, owned, no shop_domain): what V8 gives every intent
     * from before H5 (10).
     */
    public static final String LAB_FINGERPRINT = "{\"completion\":\"agent\",\"owned\":true,\"transport\":\"rest\"}";

    public MerchantEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(shopDomain, "shopDomain");
        if (transport == Transport.MCP && shopDomain.isEmpty()) {
            throw new IllegalArgumentException("an MCP merchant needs a shop_domain");
        }
        if ((transport == Transport.CATALOG) != (completion == Completion.LINK)) {
            throw new IllegalArgumentException("a catalog source and a link completion go together (19)");
        }
        if (transport == Transport.CATALOG && (owned || shopDomain.isPresent())) {
            throw new IllegalArgumentException("a catalog source is not a store: never owned, no shop_domain (19, I12)");
        }
    }

    /** A lab entry: REST, completed by the agent, no shop_domain, owned only on a loopback host (I12). */
    public MerchantEntry(MerchantId id, URI baseUrl, String displayName, MerchantStatus status) {
        this(id, baseUrl, displayName, status, Transport.REST, Completion.AGENT, isLoopback(baseUrl), Optional.empty());
    }

    /**
     * The fields I12 depends on, as CanonicalJson with lower-case values: completion, owned, shop_domain (left out when
     * empty) and transport (10, 19). An intent stores it once at insert; a different value now means the entry changed
     * under the intent.
     */
    public String fingerprint() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("completion", completion.name().toLowerCase(Locale.ROOT));
        f.put("owned", owned);
        shopDomain.ifPresent(d -> f.put("shop_domain", d));
        f.put("transport", transport.name().toLowerCase(Locale.ROOT));
        return CanonicalJson.write(f);
    }

    /**
     * Whether {@code url}'s host is exactly {@code localhost}, a dotted {@code 127.x.x.x} literal or {@code [::1]}. The
     * parsed host is compared as text, with no name lookup: a look-alike name ({@code 127.0.0.1.evil.example}) and any
     * other form of the address (decimal, octal, short, IPv4-mapped) are not loopback (I12).
     */
    public static boolean isLoopback(URI url) {
        String host = Objects.requireNonNull(url, "url").getHost();
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("[::1]") || LOOPBACK_V4.matcher(h).matches();
    }
}
