package dev.ucplab.service.config;

import dev.ucplab.core.config.AgentProfile;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.ucp.UcpHttpGateway;
import dev.ucplab.ucp.registry.MerchantSettings;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads {@code MERCHANTS_FILE} ({@code merchants.yaml}, DESIGN 19) into {@link MerchantSettings}. Like
 * {@link PolicyFile}: SnakeYAML's safe constructor only, an unknown key refused, and every refusal one of the fixed
 * messages below, which name the rule and never echo a value or the file (I8). The real-merchant keys (19 "Real
 * merchants (H5)"): {@code transport}, {@code completion}, {@code owned}, {@code shop_domain}, {@code credential_ref}
 * and {@code requires_buyer_ip}. The buyer IP itself is never a file value: it is read from the environment only.
 */
final class MerchantsFile {

    static final String UNREADABLE = "MERCHANTS_FILE cannot be read";
    static final String NOT_A_MAP = "MERCHANTS_FILE must be a map with a merchants list";
    static final String UNKNOWN_KEY = "MERCHANTS_FILE has an unknown setting";
    static final String WRONG_TYPE = "MERCHANTS_FILE has a setting of the wrong type";
    static final String COUNT = "MERCHANTS_FILE must list 1 to 20 merchants";
    static final String MISSING_KEY = "MERCHANTS_FILE has a merchant without id, name or url";
    static final String BAD_ID = "MERCHANTS_FILE has a merchant id that is not 1 to 100 of A-Z a-z 0-9 _ -";
    static final String DUPLICATE_ID = "MERCHANTS_FILE repeats a merchant id";
    static final String BAD_URL = "MERCHANTS_FILE has a URL that is not an http or https URL with a host, without user info, query or fragment";
    static final String LAB_URL_NOT_LOCAL = "MERCHANTS_FILE lab_url is only allowed in the local profile";
    static final String BAD_STATUS = "MERCHANTS_FILE has a status other than active or suspended";
    static final String BAD_ENDPOINT_HOST = "MERCHANTS_FILE has an endpoint_hosts entry that is not host or host:port";
    static final String BAD_VERSION = "MERCHANTS_FILE has a ucp_versions pin the agent does not support";
    static final String BAD_HANDLER = "MERCHANTS_FILE has a payment_handler without a valid id, type, credential_type and token_ref";
    static final String BAD_TOKEN_REF = "MERCHANTS_FILE has a token_ref that is not env:NAME";
    static final String BAD_TRANSPORT = "MERCHANTS_FILE has a transport other than rest or mcp";
    static final String BAD_COMPLETION = "MERCHANTS_FILE has a completion other than agent or handoff";
    static final String BAD_SHOP_DOMAIN = "MERCHANTS_FILE has a shop_domain that is not a host name without port or path";
    static final String BAD_CREDENTIAL_REF = "MERCHANTS_FILE has a credential_ref that is not env:NAME";
    static final String MCP_INCOMPLETE = "MERCHANTS_FILE has a transport: mcp merchant without credential_ref or shop_domain";
    static final String NOT_OWNED = "MERCHANTS_FILE has a merchant off loopback without owned: true (I12)";
    static final String BUYER_IP_NOT_LOCAL = "MERCHANTS_FILE requires_buyer_ip is only allowed in the local profile";

    static final int MAX_MERCHANTS = 20;

    private static final Set<String> KEYS = Set.of("id", "name", "url", "status", "lab_url", "endpoint_hosts",
            "ucp_versions", "payment_handler", "transport", "completion", "owned", "shop_domain", "credential_ref",
            "requires_buyer_ip");
    private static final Set<String> HANDLER_KEYS = Set.of("id", "type", "credential_type", "token_ref");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Pattern HOST = Pattern.compile("(?:[A-Za-z0-9.-]{1,253}|\\[[0-9A-Fa-f:.]{2,45}\\])(?::[0-9]{1,5})?");
    private static final Pattern HANDLER_ID = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern HANDLER_WORD = Pattern.compile("[A-Za-z0-9_-]{1,50}");
    private static final Pattern TOKEN_REF = Pattern.compile("env:[A-Z_][A-Z0-9_]{0,63}");
    /** A DNS host name of two or more labels: no port, path, scheme, user info or IP literal form. */
    private static final Pattern SHOP_DOMAIN = Pattern.compile(
            "(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    private MerchantsFile() {
    }

    static List<MerchantSettings> load(Path file, AgentProfile profile) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(profile, "profile");
        Object root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (IOException | YAMLException e) {
            throw refuse(UNREADABLE);
        }
        if (root == null) {
            throw refuse(COUNT);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw refuse(NOT_A_MAP);
        }
        for (Object key : map.keySet()) {
            if (!"merchants".equals(key)) {
                throw refuse(UNKNOWN_KEY);
            }
        }
        if (!(map.get("merchants") instanceof List<?> list)) {
            throw refuse(map.containsKey("merchants") ? WRONG_TYPE : COUNT);
        }
        if (list.isEmpty() || list.size() > MAX_MERCHANTS) {
            throw refuse(COUNT);
        }
        List<MerchantSettings> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object raw : list) {
            MerchantSettings m = merchant(raw, profile);
            if (!ids.add(m.id().value())) {
                throw refuse(DUPLICATE_ID);
            }
            out.add(m);
        }
        return List.copyOf(out);
    }

    private static MerchantSettings merchant(Object raw, AgentProfile profile) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw refuse(WRONG_TYPE);
        }
        for (Object key : m.keySet()) {
            if (!(key instanceof String name) || !KEYS.contains(name)) {
                throw refuse(UNKNOWN_KEY);
            }
        }
        for (String required : List.of("id", "name", "url")) {
            if (!m.containsKey(required)) {
                throw refuse(MISSING_KEY);
            }
        }
        String id = string(m.get("id"));
        if (!ID.matcher(id).matches()) {
            throw refuse(BAD_ID);
        }
        String name = string(m.get("name"));
        URI url = uri(string(m.get("url")));
        MerchantStatus status = status(m);
        if (m.containsKey("lab_url") && profile != AgentProfile.LOCAL) {
            throw refuse(LAB_URL_NOT_LOCAL);
        }
        Optional<URI> lab = m.containsKey("lab_url") ? Optional.of(uri(string(m.get("lab_url")))) : Optional.empty();
        Transport transport = transport(m);
        Completion completion = completion(m);
        boolean owned = m.containsKey("owned") ? bool(m.get("owned")) : MerchantEntry.isLoopback(url);
        Optional<String> shopDomain = shopDomain(m);
        Optional<String> credentialRef = credentialRef(m);
        boolean buyerIp = m.containsKey("requires_buyer_ip") && bool(m.get("requires_buyer_ip"));
        if (transport == Transport.MCP && (credentialRef.isEmpty() || shopDomain.isEmpty())) {
            throw refuse(MCP_INCOMPLETE);
        }
        if (!owned && !MerchantEntry.isLoopback(url)) {
            throw refuse(NOT_OWNED);
        }
        if (buyerIp && profile != AgentProfile.LOCAL) {
            throw refuse(BUYER_IP_NOT_LOCAL);
        }
        return new MerchantSettings(new MerchantId(id), name, url, status, lab, endpointHosts(m), versions(m),
                handler(m), transport, completion, owned, shopDomain, credentialRef, buyerIp);
    }

    private static MerchantStatus status(Map<?, ?> m) {
        if (!m.containsKey("status")) {
            return MerchantStatus.ACTIVE;
        }
        return switch (string(m.get("status"))) {
            case "active" -> MerchantStatus.ACTIVE;
            case "suspended" -> MerchantStatus.SUSPENDED;
            default -> throw refuse(BAD_STATUS);
        };
    }

    private static Transport transport(Map<?, ?> m) {
        if (!m.containsKey("transport")) {
            return Transport.REST;
        }
        return switch (string(m.get("transport"))) {
            case "rest" -> Transport.REST;
            case "mcp" -> Transport.MCP;
            default -> throw refuse(BAD_TRANSPORT);
        };
    }

    private static Completion completion(Map<?, ?> m) {
        if (!m.containsKey("completion")) {
            return Completion.AGENT;
        }
        return switch (string(m.get("completion"))) {
            case "agent" -> Completion.AGENT;
            case "handoff" -> Completion.HANDOFF;
            default -> throw refuse(BAD_COMPLETION);
        };
    }

    private static Optional<String> shopDomain(Map<?, ?> m) {
        if (!m.containsKey("shop_domain")) {
            return Optional.empty();
        }
        String domain = string(m.get("shop_domain"));
        if (!SHOP_DOMAIN.matcher(domain).matches()) {
            throw refuse(BAD_SHOP_DOMAIN);
        }
        return Optional.of(domain.toLowerCase(java.util.Locale.ROOT));
    }

    private static Optional<String> credentialRef(Map<?, ?> m) {
        if (!m.containsKey("credential_ref")) {
            return Optional.empty();
        }
        String ref = string(m.get("credential_ref"));
        if (!TOKEN_REF.matcher(ref).matches()) {
            throw refuse(BAD_CREDENTIAL_REF);
        }
        return Optional.of(ref);
    }

    private static Set<String> endpointHosts(Map<?, ?> m) {
        if (!m.containsKey("endpoint_hosts")) {
            return Set.of();
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (Object o : list(m.get("endpoint_hosts"))) {
            if (!(o instanceof String host)) {
                throw refuse(WRONG_TYPE);
            }
            if (!HOST.matcher(host).matches()) {
                throw refuse(BAD_ENDPOINT_HOST);
            }
            hosts.add(host);
        }
        return hosts;
    }

    private static Set<String> versions(Map<?, ?> m) {
        if (!m.containsKey("ucp_versions")) {
            return MerchantSettings.DEFAULT_VERSIONS;
        }
        Set<String> pin = new LinkedHashSet<>();
        for (Object o : list(m.get("ucp_versions"))) {
            String version = string(o);
            if (!UcpHttpGateway.SUPPORTED_VERSIONS.contains(version)) {
                throw refuse(BAD_VERSION);
            }
            pin.add(version);
        }
        if (pin.isEmpty()) {
            throw refuse(BAD_VERSION);
        }
        return pin;
    }

    private static Optional<MerchantSettings.PaymentHandler> handler(Map<?, ?> m) {
        if (!m.containsKey("payment_handler")) {
            return Optional.empty();
        }
        if (!(m.get("payment_handler") instanceof Map<?, ?> h)) {
            throw refuse(WRONG_TYPE);
        }
        for (Object key : h.keySet()) {
            if (!(key instanceof String name) || !HANDLER_KEYS.contains(name)) {
                throw refuse(UNKNOWN_KEY);
            }
        }
        if (!h.keySet().containsAll(HANDLER_KEYS)) {
            throw refuse(BAD_HANDLER);
        }
        String id = string(h.get("id"));
        String type = string(h.get("type"));
        String credentialType = string(h.get("credential_type"));
        if (!HANDLER_ID.matcher(id).matches() || !HANDLER_WORD.matcher(type).matches()
                || !HANDLER_WORD.matcher(credentialType).matches()) {
            throw refuse(BAD_HANDLER);
        }
        String tokenRef = string(h.get("token_ref"));
        if (!TOKEN_REF.matcher(tokenRef).matches()) {
            throw refuse(BAD_TOKEN_REF);
        }
        return Optional.of(new MerchantSettings.PaymentHandler(id, type, credentialType, tokenRef));
    }

    private static URI uri(String text) {
        URI u;
        try {
            u = new URI(text);
        } catch (java.net.URISyntaxException e) {
            throw refuse(BAD_URL);
        }
        boolean http = "http".equals(u.getScheme()) || "https".equals(u.getScheme());
        if (!http || u.getHost() == null || u.getUserInfo() != null || u.getRawQuery() != null
                || u.getRawFragment() != null) {
            throw refuse(BAD_URL);
        }
        return u;
    }

    private static String string(Object value) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        throw refuse(WRONG_TYPE);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw refuse(WRONG_TYPE);
    }

    private static List<?> list(Object value) {
        if (value instanceof List<?> l) {
            return l;
        }
        throw refuse(WRONG_TYPE);
    }

    private static IllegalStateException refuse(String message) {
        return new IllegalStateException(message);
    }
}
