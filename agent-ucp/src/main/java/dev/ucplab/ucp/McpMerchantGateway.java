package dev.ucplab.ucp;

import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.DiscoveryFailedException;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.core.merchant.UpdateCheckout;
import dev.ucplab.ucp.auth.ClientCredentials;
import dev.ucplab.ucp.auth.TokenProvider;
import dev.ucplab.ucp.auth.TokenResult;
import dev.ucplab.ucp.mcp.McpEnvelope;
import dev.ucplab.ucp.mcp.McpTransport;
import dev.ucplab.ucp.mcp.StoreHeaders;
import dev.ucplab.ucp.registry.BuyerIp;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link MerchantGateway} over MCP for one store (8.4, 8.5, 19, 26.4; H5 C6 ★). Bound at construction to an entry with
 * {@code transport: mcp}; {@link #discover} refuses any other id.
 *
 * <p><b>Discovery.</b> The profile is a plain GET of {@code /.well-known/ucp} at the entry's base URL, with the retry
 * rules of {@code get} and no {@code Authorization}; it is cached for 5 minutes (section 20) as over REST. The endpoint
 * is the {@code ucp.services["dev.ucp.shopping"]} entry whose {@code transport} is {@code mcp}. It must be on
 * {@code shop_domain} (I12) and on the base URL's host and port or one the registry lists ({@code endpoint_hosts}), with
 * the base URL's scheme ({@code endpoint_not_allowed}); the profile must name a version the merchant is pinned to and the
 * agent speaks (26.2) and the checkout capability; {@code handler_missing} applies only to {@code completion: agent}.
 * Before every call, a get and a catalog read included, the endpoint is checked again, locally, with no I/O (8.5, I8).
 *
 * <p><b>Calls.</b> Each is one JSON-RPC {@code tools/call} ({@link McpEnvelope}) through {@link RetryExecutor}, over
 * {@link StoreHeaders} (the access token, and {@code Shopify-Buyer-IP} when the entry asks for it) over
 * {@link McpTransport}: the retry matrix, the classifier, the breaker and the limiter are the REST path's. {@code get} is
 * the reconciliation read and bypasses the breaker (8.3). A token is asked for before a call: none means nothing is
 * sent ({@code NotSent}). A 401 drops the refused token; a read is then tried once more with a new one, as a new
 * {@link RetryExecutor} run (a new {@code Request-Id}); a mutating call is never resent (8.2 retries no 401).
 *
 * <p><b>No complete.</b> 8.4 offers no complete over MCP and N1 rules one out at the store: {@link #complete} returns
 * {@code NotSent} and sends nothing. It is the second guard: startup refuses {@code transport: mcp} with
 * {@code completion: agent} (19), and the orchestrator takes 7.2's {@code complete_unavailable} row before a complete
 * attempt is written.
 *
 * <p><b>Safety.</b> A reply about another session than the one asked for is not trusted, as over REST. The client
 * credentials (the whole variable value and the secret on its own, 26.4) and the buyer IP are scrubbed from every cause,
 * error content and message before an outcome leaves this class, and so is any {@code Authorization} value (I8). The
 * gateway logs nothing.
 */
public final class McpMerchantGateway implements MerchantGateway {

    /** The cause of {@link #complete}: nothing was sent (8.4, N1). */
    public static final String NO_COMPLETE = "no complete over MCP (8.4, N1)";
    private static final Duration PROFILE_TTL = Duration.ofMinutes(5);
    private static final Pattern AGENT_PROFILE = Pattern.compile("profile=\"([^\"]+)\"");

    private record Problem(String reason, String text) {
        static Problem of(String code) {
            return new Problem(code, code);
        }
    }

    private record Cached(MerchantProfile profile, Instant fetchedAt, String version) {}

    /** A profile read: the profile it cached, or why there is none. The caller never reads the cache back. */
    private sealed interface Refreshed {
        record Fresh(Cached cached) implements Refreshed {}

        record Refused(Problem problem) implements Refreshed {}
    }

    private final MerchantEntry merchant;
    private final String shopDomain;
    private final URI base;
    private final Set<String> allowedHostPorts;
    private final String paymentHandlerId;
    private final Set<String> ucpVersions;
    private final URI agentProfile;
    private final Clock clock;
    private final IdGenerator ids;
    private final TokenProvider tokens;
    /** The credentials and the buyer IP (26.4, 8.4); each call adds the token it carried. */
    private final List<String> secrets;
    private final Redactor redactor;
    private final RetryExecutor discovery;
    private final RetryExecutor tools;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    /**
     * @param transport the plain HTTP transport; this class puts {@link StoreHeaders} and {@link McpTransport} over it
     * @param endpointHosts {@code host[:port]} strings besides the base URL's that the profile's endpoint may use (8.5)
     * @param paymentHandlerId the handler a {@code completion: agent} entry's profile must list (19)
     * @param ucpVersions the versions the operator pins for this merchant (26.2), each one the agent supports
     * @param tokens the merchant's access tokens (20, 26.4)
     * @param credentials the client credentials behind {@code tokens}, only so they are scrubbed (26.4)
     * @param buyerIp the operator's {@code BUYER_IP}, present exactly when the entry says {@code requires_buyer_ip}
     * @throws IllegalArgumentException if the entry is not an MCP entry, a version is not supported, or the
     *     configuration's {@code UCP-Agent} names no profile
     */
    public McpMerchantGateway(
            MerchantEntry merchant, UcpConfig config, HttpTransport transport, CircuitBreakers breakers, Clock clock,
            Sleeper sleeper, RandomGenerator random, IdGenerator ids, Set<String> endpointHosts,
            String paymentHandlerId, Set<String> ucpVersions, TokenProvider tokens, ClientCredentials credentials,
            Optional<BuyerIp> buyerIp) {
        this.merchant = Objects.requireNonNull(merchant, "merchant");
        if (merchant.transport() != Transport.MCP) {
            throw new IllegalArgumentException("an MCP gateway is for a transport: mcp merchant");
        }
        this.shopDomain = merchant.shopDomain().orElseThrow().toLowerCase(Locale.ROOT);
        Objects.requireNonNull(config, "config");
        this.paymentHandlerId = Objects.requireNonNull(paymentHandlerId, "paymentHandlerId");
        Set<String> pinned = Set.copyOf(Objects.requireNonNull(ucpVersions, "ucpVersions"));
        if (pinned.isEmpty()) {
            throw new IllegalArgumentException("a merchant is pinned to at least one UCP version");
        }
        for (String v : pinned) {
            if (!UcpHttpGateway.SUPPORTED_VERSIONS.contains(v)) {
                throw new IllegalArgumentException("unsupported UCP version " + v);
            }
        }
        this.ucpVersions = pinned;
        Matcher profile = AGENT_PROFILE.matcher(config.ucpAgent());
        if (!profile.find()) {
            throw new IllegalArgumentException("the UCP-Agent setting names no profile (8.4)");
        }
        this.agentProfile = URI.create(profile.group(1));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(buyerIp, "buyerIp");
        List<String> secrets = new ArrayList<>(Objects.requireNonNull(credentials, "credentials").redactable());
        buyerIp.ifPresent(ip -> secrets.add(ip.value()));
        this.secrets = List.copyOf(secrets);
        this.redactor = new Redactor(this.secrets);
        this.base = UcpHttpGateway.stripTrailingSlash(merchant.baseUrl());
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(UcpHttpGateway.hostPort(base));
        for (String h : Objects.requireNonNull(endpointHosts, "endpointHosts")) {
            allowed.add(UcpHttpGateway.normalizeHostPort(h, base.getScheme()));
        }
        this.allowedHostPorts = Set.copyOf(allowed);
        Objects.requireNonNull(transport, "transport");
        Gate gate = Objects.requireNonNull(breakers, "breakers").gateFor(merchant.id());
        this.discovery = new RetryExecutor(config, new StoreHeaders(transport, Optional.empty(), buyerIp),
                new FailureClassifier(clock), gate, sleeper, random, ids);
        this.tools = new RetryExecutor(config,
                new StoreHeaders(new McpTransport(transport), Optional.of(tokens), buyerIp),
                new FailureClassifier(clock), gate, sleeper, random, ids);
    }

    // ---------------------------------------------------------------- discovery

    @Override
    public MerchantProfile discover(MerchantId id) {
        Objects.requireNonNull(id, "id");
        if (!id.equals(merchant.id())) {
            throw new IllegalArgumentException("this gateway is bound to " + merchant.id() + ", not " + id);
        }
        Cached cached = cache.get();
        if (cached != null && fresh(cached)) {
            return cached.profile();
        }
        return switch (refresh(false)) {
            case Refreshed.Fresh f -> f.cached().profile();
            case Refreshed.Refused r -> throw new DiscoveryFailedException(
                    "discovery failed for " + merchant.id() + ": " + r.problem().text(), r.problem().reason());
        };
    }

    private boolean fresh(Cached c) {
        return clock.instant().isBefore(c.fetchedAt().plus(PROFILE_TTL));
    }

    private Refreshed refresh(boolean bypassBreaker) {
        ApiCall call = new ApiCall(AttemptOp.GET, "GET", URI.create(base + "/.well-known/ucp"), Optional.empty(),
                Optional.empty(), bypassBreaker);
        return switch (discovery.fetch(call)) {
            case RetryExecutor.Fetched.Failed f -> new Refreshed.Refused(
                    new Problem(UcpHttpGateway.UNREACHABLE, redactor.scrub(UcpHttpGateway.describe(f.outcome()))));
            case RetryExecutor.Fetched.Body b -> {
                try {
                    MerchantProfile profile = ProfileParser.parse(merchant.id(), b.reply().body(), "mcp");
                    // The constructor refuses a pinned version the agent does not support.
                    Optional<Problem> problem = ucpVersions.contains(profile.ucpVersion())
                            ? incompatible(profile) : Optional.of(Problem.of(UcpHttpGateway.INCOMPATIBLE_VERSION));
                    if (problem.isPresent()) {
                        yield new Refreshed.Refused(problem.get());
                    }
                    Cached fresh = new Cached(profile, clock.instant(), profile.ucpVersion());
                    cache.set(fresh);
                    yield new Refreshed.Fresh(fresh);
                } catch (IllegalArgumentException e) {
                    yield new Refreshed.Refused(
                            new Problem(UcpHttpGateway.INVALID_PROFILE, "invalid profile: " + e.getMessage()));
                }
            }
        };
    }

    private Optional<Problem> incompatible(MerchantProfile p) {
        if (!p.capabilities().contains(UcpHttpGateway.CHECKOUT_CAPABILITY)) {
            return Optional.of(new Problem(UcpHttpGateway.CAPABILITY_MISSING, "checkout capability missing"));
        }
        if (!endpointAllowed(p.checkoutEndpoint())) {
            return Optional.of(Problem.of(UcpHttpGateway.ENDPOINT_NOT_ALLOWED));
        }
        if (merchant.completion() == Completion.AGENT && !p.paymentHandlers().contains(paymentHandlerId)) {
            return Optional.of(Problem.of(UcpHttpGateway.HANDLER_MISSING));
        }
        return Optional.empty();
    }

    /**
     * 8.5 and I12, locally: the store's own host, a host and port the operator allows, and the base URL's scheme. The
     * endpoint has a host: {@link ProfileParser} refuses one without.
     */
    private boolean endpointAllowed(URI endpoint) {
        return endpoint.getHost().toLowerCase(Locale.ROOT).equals(shopDomain)
                && allowedHostPorts.contains(UcpHttpGateway.hostPort(endpoint))
                && base.getScheme().equalsIgnoreCase(endpoint.getScheme());
    }

    /** A call for an intent in flight uses the cached profile whatever its age; a new intent needs a fresh one (19). */
    private Refreshed profileFor(boolean newIntent) {
        Cached cached = cache.get();
        if (cached != null && (!newIntent || fresh(cached))) {
            return new Refreshed.Fresh(cached);
        }
        return refresh(!newIntent);
    }

    // ---------------------------------------------------------------- the calls

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(key, "key");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("checkout", CanonicalJson.readObject(CheckoutWire.createBody(request)));
        return send(AttemptOp.CREATE, "create_checkout", args, Optional.empty(), Optional.of(key), true);
    }

    // TODO(VERIFY 9): the spike recorded create_checkout ({checkout}) and cancel_checkout ({id}) only; that
    // update_checkout takes {id, checkout} and get_checkout takes {id} follows them and is not yet recorded.
    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(key, "key");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id", id.value());
        args.put("checkout", CanonicalJson.readObject(CheckoutWire.updateBody(request)));
        return send(AttemptOp.UPDATE, "update_checkout", args, Optional.of(id), Optional.of(key), false);
    }

    /** Never sent (8.4, N1): the second guard behind startup and 7.2's {@code complete_unavailable}. */
    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(key, "key");
        return new CallOutcome.NotSent<>(NO_COMPLETE);
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        return send(AttemptOp.CANCEL, "cancel_checkout", Map.of("id", id.value()), Optional.of(id), Optional.of(key),
                false);
    }

    /** The reconciliation read (7.5): no key, and it bypasses the circuit breaker (8.3). */
    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        Objects.requireNonNull(id, "id");
        return send(AttemptOp.GET, "get_checkout", Map.of("id", id.value()), Optional.of(id), Optional.empty(), false);
    }

    private CallOutcome<CheckoutView> send(
            AttemptOp op, String tool, Map<String, Object> args, Optional<SessionId> session,
            Optional<IdempotencyKey> key, boolean newIntent) {
        Refreshed refreshed = profileFor(newIntent);
        if (refreshed instanceof Refreshed.Refused r) {
            return new CallOutcome.NotSent<>(redactor.scrub("merchant profile unavailable: " + r.problem().text()));
        }
        Cached profile = ((Refreshed.Fresh) refreshed).cached();
        URI endpoint = profile.profile().checkoutEndpoint();
        if (!endpointAllowed(endpoint)) {
            // 8.5: again before every call, a get included (it carries the token and the buyer IP, I8), locally (I12).
            return new CallOutcome.NotSent<>(UcpHttpGateway.ENDPOINT_NOT_ALLOWED);
        }
        TokenResult token = tokens.current();
        if (token instanceof TokenResult.Failed failed) {
            return new CallOutcome.NotSent<>(redactor.scrub("no access token: " + failed.reason()));
        }
        ApiCall call = new ApiCall(op, "POST", endpoint, key,
                Optional.of(McpEnvelope.toolCall(ids, tool, args, agentProfile, key)), op == AttemptOp.GET,
                Optional.of(profile.version()));
        CallOutcome<CheckoutView> outcome = withToken(token).scrub(tools.execute(call));
        if (op == AttemptOp.GET && unauthorized(outcome)) {
            // A read refused for its token: the token is dropped (StoreHeaders); one more run with a new one (C5, 8.2).
            outcome = withToken(tokens.current()).scrub(withToken(token).scrub(tools.execute(call)));
        }
        outcome = UcpHttpGateway.offered(outcome, profile.profile());
        if (newIntent && outcome instanceof CallOutcome.ProtocolError<CheckoutView> e && e.httpStatus() >= 400
                && e.httpStatus() < 500 && e.httpStatus() != 429) {
            cache.set(null);    // section 20, as over REST
        }
        return session.isPresent() ? UcpHttpGateway.checkSession(op, session.get(), outcome) : outcome;
    }

    private static boolean unauthorized(CallOutcome<?> outcome) {
        return outcome instanceof CallOutcome.ProtocolError<?> e && e.httpStatus() == 401;
    }

    // ---------------------------------------------------------------- the catalog

    /**
     * One read tool call for {@link McpCatalogGateway} ({@code search_catalog}): the same profile, token, headers,
     * executor and redactor as the checkout calls; a refused token is retried once, as for {@code get}.
     */
    RetryExecutor.Fetched read(String tool, Map<String, Object> args) {
        Refreshed refreshed = profileFor(false);
        if (refreshed instanceof Refreshed.Refused r) {
            return new RetryExecutor.Fetched.Failed(
                    new CallOutcome.NotSent<>(redactor.scrub("merchant profile unavailable: " + r.problem().text())));
        }
        Cached profile = ((Refreshed.Fresh) refreshed).cached();
        URI endpoint = profile.profile().checkoutEndpoint();
        if (!endpointAllowed(endpoint)) {
            // 8.5, I12, I8: as before every checkout call.
            return new RetryExecutor.Fetched.Failed(new CallOutcome.NotSent<>(UcpHttpGateway.ENDPOINT_NOT_ALLOWED));
        }
        TokenResult token = tokens.current();
        if (token instanceof TokenResult.Failed failed) {
            return new RetryExecutor.Fetched.Failed(
                    new CallOutcome.NotSent<>(redactor.scrub("no access token: " + failed.reason())));
        }
        ApiCall call = new ApiCall(AttemptOp.GET, "POST", endpoint, Optional.empty(),
                Optional.of(McpEnvelope.toolCall(ids, tool, args, agentProfile, Optional.empty())), false,
                Optional.of(profile.version()));
        RetryExecutor.Fetched fetched = scrubbed(withToken(token), tools.fetch(call));
        if (fetched instanceof RetryExecutor.Fetched.Failed f && unauthorized(f.outcome())) {
            fetched = scrubbed(withToken(token), scrubbed(withToken(tokens.current()), tools.fetch(call)));
        }
        return fetched;
    }

    private static RetryExecutor.Fetched scrubbed(Redactor redactor, RetryExecutor.Fetched fetched) {
        return fetched instanceof RetryExecutor.Fetched.Failed f
                ? new RetryExecutor.Fetched.Failed(redactor.scrub(f.outcome())) : fetched;
    }

    /** The gateway's redactor, also masking the access token a call carried (I8: the store sees it). */
    private Redactor withToken(TokenResult token) {
        if (!(token instanceof TokenResult.Ok ok)) {
            return redactor;
        }
        List<String> all = new ArrayList<>(secrets);
        all.add(ok.token().authorizationHeader().substring("Bearer ".length()));
        return new Redactor(all);
    }

    /**
     * A test seam: caches {@code profile} as if discovery had just read it, without discovery's checks, so a test can
     * show that the re-check before each call (8.5, I12) refuses an endpoint that got past them. Not called outside
     * tests.
     */
    McpMerchantGateway withUncheckedProfile(MerchantProfile profile) {
        cache.set(new Cached(Objects.requireNonNull(profile, "profile"), clock.instant(), profile.ucpVersion()));
        return this;
    }

    /** The merchant this gateway is bound to. */
    public MerchantEntry merchant() {
        return merchant;
    }

    @Override
    public String toString() {
        return "McpMerchantGateway[" + merchant.id().value() + "]";
    }
}
