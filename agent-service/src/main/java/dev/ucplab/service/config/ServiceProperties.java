package dev.ucplab.service.config;

import dev.ucplab.core.agent.ModelKey;
import dev.ucplab.core.approval.ApprovalSecret;
import dev.ucplab.core.config.AgentProfile;
import dev.ucplab.core.intent.BuyerCountry;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.core.policy.PolicyConfig;
import dev.ucplab.ucp.GlobalCatalogGateway;
import dev.ucplab.ucp.UcpHttpGateway;
import dev.ucplab.ucp.auth.ClientCredentials;
import dev.ucplab.ucp.registry.BuyerIp;
import dev.ucplab.ucp.registry.MerchantSettings;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The service's settings (25.4), read once from the {@link Environment} and checked before anything starts. Every
 * refusal is a fixed message that names the setting and never its value (I8). The approval secret is kept only to build
 * the {@link ApprovalSecret} and the cursor key in {@link StackConfiguration}; {@link #toString} never shows it.
 *
 * <p>Settings: {@code AGENT_PROFILE} (default {@code local}); {@code DATABASE_URL}, a {@code jdbc:sqlite:} file URL
 * (required in prod, else {@code jdbc:sqlite:data/ucplab.db}); {@code APPROVAL_SECRET} (required, at least 32 bytes,
 * and in prod not a {@code change-me} placeholder, 9.2); {@code POLICY_FILE} (optional, {@link PolicyFile}; prod refuses
 * {@code approval_required: false}, P6); {@code MERCHANT_URL}, the one merchant of M6 as the agent reaches it (25.4:
 * "M6 configures its one merchant directly"; default the proxy at {@code http://127.0.0.1:9101}); {@code LOCAL_USERS},
 * the names the local dev sign-in offers (18.8, default {@code alice,bob}); {@code MERCHANT_LAB_URL} (the merchant direct, for the local lab, default
 * {@code http://127.0.0.1:9001}); {@code AGENT_MODEL} ({@code scripted} by default, or
 * {@code gemini}, which needs {@code GEMINI_API_KEY}; {@code GEMINI_BASE_URL} may point it at a loopback stub);
 * {@code PUBLIC_ORIGIN}, a
 * comma-separated list of the origins a browser shows for this service, which the dev sign-in checks {@code Origin}
 * against (m6-plan section 4; default {@code http://localhost:8080,http://127.0.0.1:8080}).
 */
public final class ServiceProperties {

    static final String PROFILE_INVALID = "AGENT_PROFILE must be local, demo or prod";
    static final String DATABASE_REQUIRED = "DATABASE_URL is required in the prod profile";
    static final String DATABASE_INVALID = "DATABASE_URL must be a jdbc:sqlite: file URL";
    static final String SECRET_REQUIRED = "APPROVAL_SECRET is required";
    static final String SECRET_SHORT = "APPROVAL_SECRET must be at least " + ApprovalSecret.MIN_BYTES + " bytes";
    static final String SECRET_PLACEHOLDER = "APPROVAL_SECRET is a placeholder, which the prod profile refuses";
    static final String APPROVAL_OFF = "POLICY_FILE turns approval off, which the prod profile refuses";
    static final String MERCHANT_INVALID = "MERCHANT_URL must be an http URL";
    static final String MERCHANT_NOT_LOOPBACK =
            "MERCHANT_URL must be on a loopback host (I12); list any other merchant in MERCHANTS_FILE with owned: true";
    static final String BUYER_IP_REQUIRED = "BUYER_IP must be set for a merchant with requires_buyer_ip";
    static final String BUYER_IP_NOT_LOCAL = "BUYER_IP is only accepted in the local profile";
    static final String CREDENTIAL_UNSET = "a merchant's credential_ref names a variable that is not set";
    /** 26.4: the value is client_id:client_secret; the reason never holds it (I8). */
    static final String CREDENTIAL_MALFORMED = "a merchant's credential_ref variable is not client_id:client_secret";
    static final String TOKEN_ENDPOINT_INVALID =
            "SHOPIFY_TOKEN_ENDPOINT must be an https URL with a host, and no user info or query";
    static final String AGENT_VERSION_UNSUPPORTED = "UCP_AGENT_VERSION is not a version the agent supports";
    static final String AGENT_PROFILE_DEFAULT =
            "UCP_AGENT_PROFILE must be set, and not to its default, for an MCP merchant or one off loopback";
    /** 19, 26.7: the Global Catalog answers at the version of Shopify's sample profile. */
    static final String CATALOG_VERSION = "UCP_AGENT_VERSION must be 2026-04-08 for a transport: catalog merchant";
    static final String AGENT_PROFILE_INVALID = "UCP_AGENT_PROFILE must be an https URL with a host, and no user info";
    /** The agent profile's URL (26.2, 8.4) unless UCP_AGENT_PROFILE says otherwise. */
    static final URI DEFAULT_AGENT_PROFILE = URI.create("https://ucplab.dev/agent");
    /** The version the default agent profile declares (26.2). */
    static final String DEFAULT_AGENT_VERSION = "2026-08-25";
    /** The token endpoint the H5 spike used (26.4). */
    static final URI DEFAULT_TOKEN_ENDPOINT = URI.create("https://api.shopify.com/auth/access_token");
    static final String USERS_INVALID = "LOCAL_USERS must be a comma-separated list of lowercase names";
    static final String LAB_URL_INVALID = "MERCHANT_LAB_URL must be an http URL";
    static final String MODEL_INVALID = "AGENT_MODEL must be scripted or gemini";
    static final String MODEL_KEY_REQUIRED = "AGENT_MODEL=gemini needs GEMINI_API_KEY";
    /** 6.5: a key the API's authentication header cannot carry; fixed, so it never shows the key. */
    static final String MODEL_KEY_UNSAFE = "GEMINI_API_KEY holds a character an HTTP header cannot carry (only visible ASCII)";
    static final String MODEL_URL_INVALID = "GEMINI_BASE_URL must be an http URL on a loopback address";
    static final String ORIGIN_INVALID = "PUBLIC_ORIGIN must be a scheme, host and optional port, with no path";
    /** 23.1: fixed, as every refusal here is. */
    static final String BUYER_COUNTRY_INVALID =
            "BUYER_COUNTRY must be two upper-case letters naming a country with one currency (ISO 3166-1, ISO 4217)";

    /** Which model drives the agent (25.4 {@code AGENT_MODEL}); {@code scripted} needs no key and no network. */
    public enum AgentModel { SCRIPTED, GEMINI }

    /** The id of M6's one merchant (25.3 {@code merchant-a}). */
    public static final MerchantId MERCHANT = new MerchantId("merchant-a");

    private static final String SQLITE = "jdbc:sqlite:";
    private static final String DEFAULT_DATABASE = SQLITE + "data/ucplab.db";
    /** Both loopback spellings of the console's address, so either works in a browser (Stage 3 review, point 2). */
    private static final String DEFAULT_ORIGINS = "http://localhost:8080,http://127.0.0.1:8080";
    private static final String DEFAULT_MERCHANT_URL = "http://127.0.0.1:9101";
    /** The merchant itself, where the lab reads ground truth; the proxy never forwards {@code /_lab} (12). */
    private static final String DEFAULT_LAB_URL = "http://127.0.0.1:9001";
    private static final Pattern LOCAL_NAME = Pattern.compile("[a-z][a-z0-9]{0,31}");

    private final AgentProfile profile;
    private final Path databaseFile;
    private final byte[] approvalSecret;
    private final ApprovalSecret approvalKey;
    private final PolicyConfig policy;
    private final MerchantEntry merchant;
    private final List<MerchantSettings> merchants;
    private final List<String> localUsers;
    private final List<String> publicOrigins;
    private final AgentModel agentModel;
    private final Optional<ModelKey> modelKey;
    private final Optional<URI> modelBaseUrl;
    private final URI merchantLabUrl;
    private final Optional<BuyerIp> buyerIp;
    private final URI shopifyTokenEndpoint;
    private final URI ucpAgentProfile;
    private final String ucpAgentVersion;
    private final BuyerCountry buyerCountry;

    private ServiceProperties(AgentProfile profile, Path databaseFile, byte[] approvalSecret, ApprovalSecret approvalKey,
                              PolicyConfig policy, MerchantEntry merchant, List<MerchantSettings> merchants,
                              List<String> localUsers,
                              List<String> publicOrigins, AgentModel agentModel, Optional<ModelKey> modelKey,
                              Optional<URI> modelBaseUrl, URI merchantLabUrl, Optional<BuyerIp> buyerIp,
                              URI shopifyTokenEndpoint, URI ucpAgentProfile, String ucpAgentVersion,
                              BuyerCountry buyerCountry) {
        this.profile = profile;
        this.databaseFile = databaseFile;
        this.approvalSecret = approvalSecret;
        this.approvalKey = approvalKey;
        this.policy = policy;
        this.merchant = merchant;
        this.merchants = merchants;
        this.localUsers = localUsers;
        this.publicOrigins = publicOrigins;
        this.agentModel = agentModel;
        this.modelKey = modelKey;
        this.modelBaseUrl = modelBaseUrl;
        this.buyerIp = buyerIp;
        this.merchantLabUrl = merchantLabUrl;
        this.shopifyTokenEndpoint = shopifyTokenEndpoint;
        this.ucpAgentProfile = ucpAgentProfile;
        this.ucpAgentVersion = ucpAgentVersion;
        this.buyerCountry = buyerCountry;
    }

    /** Reads and checks every setting; throws {@link IllegalStateException} with a fixed message on the first refusal. */
    public static ServiceProperties from(Environment env) {
        Objects.requireNonNull(env, "env");
        AgentProfile profile;
        try {
            profile = AgentProfile.parse(env.get("AGENT_PROFILE").orElse("local"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PROFILE_INVALID);
        }
        ProfileGuard.springProfile(profile);

        if (profile == AgentProfile.PROD && env.get("DATABASE_URL").isEmpty()) {
            throw new IllegalStateException(DATABASE_REQUIRED);
        }
        String url = env.get("DATABASE_URL").orElse(DEFAULT_DATABASE);
        if (!url.startsWith(SQLITE) || url.length() == SQLITE.length() || url.contains("?")
                || url.substring(SQLITE.length()).startsWith(":")) {
            throw new IllegalStateException(DATABASE_INVALID);
        }
        Path databaseFile = Path.of(url.substring(SQLITE.length()));

        String secret = env.get("APPROVAL_SECRET").orElseThrow(() -> new IllegalStateException(SECRET_REQUIRED));
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < ApprovalSecret.MIN_BYTES) {
            throw new IllegalStateException(SECRET_SHORT);
        }
        ApprovalSecret approvalKey;
        try {
            approvalKey = profile == AgentProfile.PROD ? ApprovalSecret.ofProduction(secret) : ApprovalSecret.of(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(SECRET_PLACEHOLDER);
        }

        PolicyConfig policy = env.get("POLICY_FILE").map(f -> PolicyFile.load(Path.of(f)))
                .orElseGet(PolicyConfig::defaults);
        try {
            policy.requireValidFor(profile);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(APPROVAL_OFF);
        }

        URI merchantUrl;
        try {
            merchantUrl = URI.create(env.get("MERCHANT_URL").orElse(DEFAULT_MERCHANT_URL));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(MERCHANT_INVALID);
        }
        if (!"http".equals(merchantUrl.getScheme()) && !"https".equals(merchantUrl.getScheme())
                || merchantUrl.getHost() == null) {
            throw new IllegalStateException(MERCHANT_INVALID);
        }
        MerchantEntry merchant = new MerchantEntry(MERCHANT, merchantUrl, "Merchant A", MerchantStatus.ACTIVE);

        List<String> localUsers = Arrays.stream(env.get("LOCAL_USERS").orElse("alice,bob").split(",", -1))
                .map(String::trim).toList();
        if (localUsers.stream().anyMatch(n -> !LOCAL_NAME.matcher(n).matches())
                || localUsers.stream().distinct().count() != localUsers.size()) {
            throw new IllegalStateException(USERS_INVALID);
        }

        AgentModel agentModel = switch (env.get("AGENT_MODEL").orElse("scripted")) {
            case "scripted" -> AgentModel.SCRIPTED;
            case "gemini" -> AgentModel.GEMINI;
            default -> throw new IllegalStateException(MODEL_INVALID);
        };
        // The key is read here, through Environment, and nowhere else (I8). It is held only as a ModelKey.
        Optional<ModelKey> modelKey = env.get("GEMINI_API_KEY").map(ModelKey::of);
        if (agentModel == AgentModel.GEMINI && modelKey.isEmpty()) {
            throw new IllegalStateException(MODEL_KEY_REQUIRED);
        }
        if (agentModel == AgentModel.GEMINI && !modelKey.get().visibleAscii()) {
            throw new IllegalStateException(MODEL_KEY_UNSAFE);
        }
        URI labUrl;
        try {
            labUrl = URI.create(env.get("MERCHANT_LAB_URL").orElse(DEFAULT_LAB_URL));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(LAB_URL_INVALID);
        }
        if (!"http".equals(labUrl.getScheme()) && !"https".equals(labUrl.getScheme()) || labUrl.getHost() == null) {
            throw new IllegalStateException(LAB_URL_INVALID);
        }
        Optional<URI> modelBaseUrl = env.get("GEMINI_BASE_URL").map(ServiceProperties::loopbackUrl);

        // DESIGN 19: MERCHANTS_FILE lists the merchants; without it the one MERCHANT_URL merchant is the whole registry,
        // exactly as in M6. The first merchant of the file is the one the single-merchant settings ({@link #merchant},
        // {@link #merchantLabUrl}) and an omitted merchant_id of the lab routes mean.
        MerchantEntry fallback = merchant;
        URI fallbackLab = labUrl;
        List<MerchantSettings> merchants = env.get("MERCHANTS_FILE").map(f -> MerchantsFile.load(Path.of(f), profile))
                .orElseGet(() -> List.of(MerchantSettings.single(fallback, fallbackLab)));
        if (env.get("MERCHANTS_FILE").isEmpty() && !MerchantEntry.isLoopback(merchantUrl)) {
            // The fallback merchant cannot say owned: true, so off loopback it would be unowned (I12).
            throw new IllegalStateException(MERCHANT_NOT_LOOPBACK);
        }
        Optional<BuyerIp> buyerIp = buyerIp(env, profile, merchants);
        for (MerchantSettings m : merchants) {
            if (m.credentialRef().isPresent()) {
                Optional<String> value = env.get(m.credentialRef().get().substring("env:".length()));
                if (value.isEmpty()) {
                    throw new IllegalStateException(CREDENTIAL_UNSET);
                }
                try {
                    ClientCredentials.parse(value.get());
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException(CREDENTIAL_MALFORMED);   // ClientCredentials' reason, never the value
                }
            }
        }
        URI tokenEndpoint = tokenEndpoint(env);
        URI agentProfile = agentProfile(env);
        // 19, 26.2: the default's host is not ours, so it serves the loopback lab merchants only.
        boolean realStore = merchants.stream()
                .anyMatch(m -> m.transport() != Transport.REST || !MerchantEntry.isLoopback(m.url()));
        if (realStore && agentProfile.equals(DEFAULT_AGENT_PROFILE)) {
            throw new IllegalStateException(AGENT_PROFILE_DEFAULT);
        }
        String agentVersion = env.get("UCP_AGENT_VERSION").orElse(DEFAULT_AGENT_VERSION);
        if (!UcpHttpGateway.SUPPORTED_VERSIONS.contains(agentVersion)) {
            throw new IllegalStateException(AGENT_VERSION_UNSUPPORTED);
        }
        // 19, 26.7: the Global Catalog answers at the version of Shopify's sample profile only.
        if (merchants.stream().anyMatch(m -> m.transport() == Transport.CATALOG)
                && !GlobalCatalogGateway.VERSION.equals(agentVersion)) {
            throw new IllegalStateException(CATALOG_VERSION);
        }
        MerchantSettings first = merchants.getFirst();

        return new ServiceProperties(profile, databaseFile, secretBytes, approvalKey, policy, first.entry(first.fileStatus()), merchants, localUsers,
                origins(env.get("PUBLIC_ORIGIN").orElse(DEFAULT_ORIGINS)), agentModel,
                agentModel == AgentModel.GEMINI ? modelKey : Optional.empty(), modelBaseUrl, first.labUrl().orElse(fallbackLab), buyerIp, tokenEndpoint,
                agentProfile, agentVersion, buyerCountry(env));
    }

    /** 23.1, 26.7: the operator's default buyer country, US when unset; a code BuyerCountry refuses stops startup. */
    private static BuyerCountry buyerCountry(Environment env) {
        Optional<String> code = env.get("BUYER_COUNTRY");
        if (code.isEmpty()) {
            return BuyerCountry.US;
        }
        try {
            return new BuyerCountry(code.get());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(BUYER_COUNTRY_INVALID);
        }
    }

    /**
     * UCP_AGENT_PROFILE (26.2, 8.4): the agent profile's URL, sent in {@code UCP-Agent} and in each MCP call's meta. An
     * https URL with a host and no user info; the refusal is a fixed message without the value.
     */
    private static URI agentProfile(Environment env) {
        Optional<String> raw = env.get("UCP_AGENT_PROFILE");
        if (raw.isEmpty()) {
            return DEFAULT_AGENT_PROFILE;
        }
        URI uri;
        try {
            uri = new URI(raw.get());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(AGENT_PROFILE_INVALID);   // the exception's message holds the input
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new IllegalStateException(AGENT_PROFILE_INVALID);
        }
        return uri;
    }

    /**
     * SHOPIFY_TOKEN_ENDPOINT (26.4): the spike's endpoint by default; otherwise an https URL with a host and no user
     * info or query. The refusal is a fixed message without the value.
     */
    private static URI tokenEndpoint(Environment env) {
        Optional<String> raw = env.get("SHOPIFY_TOKEN_ENDPOINT");
        if (raw.isEmpty()) {
            return DEFAULT_TOKEN_ENDPOINT;
        }
        URI uri;
        try {
            uri = new URI(raw.get());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(TOKEN_ENDPOINT_INVALID);   // the exception's message holds the input
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null) {
            throw new IllegalStateException(TOKEN_ENDPOINT_INVALID);
        }
        return uri;
    }

    /**
     * BUYER_IP (8.4, 19): from the environment only, in the local profile only, a public IP literal ({@link BuyerIp});
     * required when a merchant has requires_buyer_ip. Every refusal is a fixed message without the value (I8).
     */
    private static Optional<BuyerIp> buyerIp(Environment env, AgentProfile profile, List<MerchantSettings> merchants) {
        Optional<String> raw = env.get("BUYER_IP");
        if (raw.isPresent() && profile != AgentProfile.LOCAL) {
            throw new IllegalStateException(BUYER_IP_NOT_LOCAL);
        }
        Optional<BuyerIp> ip;
        try {
            ip = raw.map(BuyerIp::parse);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage()); // one of BuyerIp's fixed reasons
        }
        if (ip.isEmpty() && merchants.stream().anyMatch(MerchantSettings::requiresBuyerIp)) {
            throw new IllegalStateException(BUYER_IP_REQUIRED);
        }
        return ip;
    }

    /** A test points the live client at a loopback stub; any other host could receive the key, so it is refused. */
    private static URI loopbackUrl(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(MODEL_URL_INVALID);
        }
        String host = uri.getHost();
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (!"http".equals(uri.getScheme()) || !loopback || uri.getRawUserInfo() != null) {
            throw new IllegalStateException(MODEL_URL_INVALID);
        }
        return uri;
    }

    private static List<String> origins(String value) {
        return Arrays.stream(value.split(",", -1)).map(String::trim).map(ServiceProperties::origin).distinct()
                .toList();
    }

    private static String origin(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(ORIGIN_INVALID);
        }
        boolean http = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        if (!http || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(uri.getRawPath() == null || uri.getRawPath().isEmpty())) {
            throw new IllegalStateException(ORIGIN_INVALID);
        }
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    public AgentProfile profile() {
        return profile;
    }

    public String springProfile() {
        return ProfileGuard.springProfile(profile);
    }

    public Path databaseFile() {
        return databaseFile;
    }

    public PolicyConfig policy() {
        return policy;
    }

    /** The first merchant (M6's one merchant, and the lab's default). */
    public MerchantEntry merchant() {
        return merchant;
    }

    /** Every configured merchant, in file order (DESIGN 19); one, from {@code MERCHANT_URL}, without a file. */
    /** The token endpoint of the MCP merchants' client credentials (26.4). Log its host only. */
    public URI shopifyTokenEndpoint() {
        return shopifyTokenEndpoint;
    }

    /** The agent's {@code UCP-Agent} value (26.2, 8.4): {@code profile="<UCP_AGENT_PROFILE>"}. */
    public String ucpAgent() {
        return "profile=\"" + ucpAgentProfile + "\"";
    }

    /** UCP_AGENT_VERSION: the version the agent profile declares, the one every MCP merchant is spoken in (26.2). */
    public String ucpAgentVersion() {
        return ucpAgentVersion;
    }

    /** The operator's BUYER_IP, when set (local profile only, 8.4). Its toString is masked (I8). */
    public Optional<BuyerIp> buyerIp() {
        return buyerIp;
    }

    public List<MerchantSettings> merchants() {
        return merchants;
    }

    /** The names the local dev sign-in offers, in order (18.8). */
    public List<String> localUsers() {
        return localUsers;
    }

    /** The origins a browser shows for this service, each as {@code scheme://host[:port]}. */
    public List<String> publicOrigins() {
        return publicOrigins;
    }

    /** The merchant as the lab reaches it directly, for ground-truth reads and resets (local profile only). */
    public URI merchantLabUrl() {
        return merchantLabUrl;
    }

    /** The operator's default buyer country (23.1 {@code BUYER_COUNTRY}), US when unset. */
    public BuyerCountry buyerCountry() {
        return buyerCountry;
    }

    public AgentModel agentModel() {
        return agentModel;
    }

    /** The live model's key: present only when {@code AGENT_MODEL=gemini}. It never prints (I8). */
    public Optional<ModelKey> modelKey() {
        return modelKey;
    }

    /** A loopback endpoint for the live client, for tests; empty means the provider's own. */
    public Optional<URI> modelBaseUrl() {
        return modelBaseUrl;
    }

    /** The key for approval tokens, built with {@code ofProduction} in prod (9.2). */
    ApprovalSecret approvalKey() {
        return approvalKey;
    }

    /** The configured secret's bytes, for the cursor key only ({@link StackConfiguration}). A copy. */
    byte[] approvalSecretBytes() {
        return approvalSecret.clone();
    }

    @Override
    public String toString() {
        return "ServiceProperties[profile=" + profile + ", approvalSecret=[redacted]]";
    }
}
