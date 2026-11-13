package dev.ucplab.service.config;

import dev.ucplab.core.approval.ApprovalSecret;
import dev.ucplab.core.config.AgentProfile;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.policy.PolicyConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
 * the names the local dev sign-in offers (18.8, default {@code alice,bob}); {@code PUBLIC_ORIGIN}, the origin a
 * browser shows for this service, which the dev sign-in checks {@code Origin} against (m6-plan section 4, default
 * {@code http://localhost:8080}).
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
    static final String USERS_INVALID = "LOCAL_USERS must be a comma-separated list of lowercase names";
    static final String ORIGIN_INVALID = "PUBLIC_ORIGIN must be a scheme, host and optional port, with no path";

    /** The id of M6's one merchant (25.3 {@code merchant-a}). */
    public static final MerchantId MERCHANT = new MerchantId("merchant-a");

    private static final String SQLITE = "jdbc:sqlite:";
    private static final String DEFAULT_DATABASE = SQLITE + "data/ucplab.db";
    private static final String DEFAULT_MERCHANT_URL = "http://127.0.0.1:9101";
    private static final Pattern LOCAL_NAME = Pattern.compile("[a-z][a-z0-9]{0,31}");

    private final AgentProfile profile;
    private final Path databaseFile;
    private final byte[] approvalSecret;
    private final ApprovalSecret approvalKey;
    private final PolicyConfig policy;
    private final MerchantEntry merchant;
    private final List<String> localUsers;
    private final String publicOrigin;

    private ServiceProperties(AgentProfile profile, Path databaseFile, byte[] approvalSecret, ApprovalSecret approvalKey,
                              PolicyConfig policy, MerchantEntry merchant, List<String> localUsers,
                              String publicOrigin) {
        this.profile = profile;
        this.databaseFile = databaseFile;
        this.approvalSecret = approvalSecret;
        this.approvalKey = approvalKey;
        this.policy = policy;
        this.merchant = merchant;
        this.localUsers = localUsers;
        this.publicOrigin = publicOrigin;
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

        return new ServiceProperties(profile, databaseFile, secretBytes, approvalKey, policy, merchant, localUsers,
                origin(env.get("PUBLIC_ORIGIN").orElse("http://localhost:8080")));
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

    public MerchantEntry merchant() {
        return merchant;
    }

    /** The names the local dev sign-in offers, in order (18.8). */
    public List<String> localUsers() {
        return localUsers;
    }

    /** The origin a browser shows for this service, as {@code scheme://host[:port]}. */
    public String publicOrigin() {
        return publicOrigin;
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
