package dev.ucplab.ucp;

import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.DiscoveryFailedException;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

/**
 * {@link MerchantGateway} over HTTP for one merchant (8.5, Appendix B). Bound at construction to the merchant's base
 * URL; {@link #discover} refuses any other id.
 *
 * <p><b>Discovery (K).</b> The profile is fetched with the retry rules of {@code get}, cached for 5 minutes (section 20),
 * and must show UCP version 2026-08-25, the capability {@code dev.ucp.shopping.checkout} and a REST endpoint on the same
 * host and port as the base URL. A create (a new intent) uses a fresh profile; if it cannot be had or is not compatible,
 * no create is sent and the answer is {@code NotSent}. The other calls belong to intents already in flight and keep using
 * the cached profile whatever its age (section 19); only if there is none do they fetch one, bypassing the breaker.
 *
 * <p><b>Requests.</b> Every call goes through {@link RetryExecutor} (8.2): the same {@code Idempotency-Key} and body
 * bytes on every attempt, a fresh {@code Request-Id}, {@code UCP-Agent} from the configuration. {@code get} is the
 * reconciliation read and bypasses the circuit breaker (8.3). An update sends exactly the body of
 * {@link CheckoutWire#updateBody}: the line ids and buyer fields of the last view (8.4). The gateway never fetches or
 * caches a checkout (section 20).
 *
 * <p><b>Safety.</b> A reply about a different session than the one asked for is not trusted (Uncertain, or ProtocolError
 * for {@code get}). The payment token of a {@code complete} body and any {@code Authorization} value are scrubbed from
 * every cause, error content and message before an outcome leaves this class (I8). The gateway logs nothing.
 */
public final class UcpHttpGateway implements MerchantGateway {

    /** The only UCP version this client speaks (R1, 26.2). */
    public static final String SUPPORTED_VERSION = "2026-08-25";
    /** The capability a merchant must offer (19). */
    public static final String CHECKOUT_CAPABILITY = "dev.ucp.shopping.checkout";
    /** The payment handler the agent's mock instrument uses (Appendix A): a merchant that lacks it cannot be paid (19). */
    public static final String PAYMENT_HANDLER = "mock_pay_1";
    /** Discovery failure reasons, fixed texts (8.5). */
    public static final String ENDPOINT_NOT_ALLOWED = "endpoint_not_allowed";
    public static final String HANDLER_MISSING = "handler_missing";
    private static final Duration PROFILE_TTL = Duration.ofMinutes(5);

    private record Cached(MerchantProfile profile, Instant fetchedAt) {}

    private final MerchantEntry merchant;
    private final URI base;
    private final String hostPort;
    private final Set<String> allowedHostPorts;
    private final Clock clock;
    private final RetryExecutor executor;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public UcpHttpGateway(
            MerchantEntry merchant, UcpConfig config, HttpTransport transport, CircuitBreakers breakers, Clock clock,
            Sleeper sleeper, RandomGenerator random, IdGenerator ids) {
        this(merchant, config, transport, breakers, clock, sleeper, random, ids, Set.of());
    }

    /**
     * @param endpointHosts {@code host[:port]} strings (https without a port is 443) besides the base URL's that the profile's checkout endpoint may use
     *     (8.5, {@code endpoint_hosts} of the registry): the operator, not the profile, decides which hosts carry
     *     checkout traffic
     */
    public UcpHttpGateway(
            MerchantEntry merchant, UcpConfig config, HttpTransport transport, CircuitBreakers breakers, Clock clock,
            Sleeper sleeper, RandomGenerator random, IdGenerator ids, Set<String> endpointHosts) {
        this.merchant = Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.base = stripTrailingSlash(merchant.baseUrl());
        this.hostPort = hostPort(base);
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(hostPort);
        for (String h : Objects.requireNonNull(endpointHosts, "endpointHosts")) {
            allowed.add(normalizeHostPort(h, base.getScheme()));
        }
        this.allowedHostPorts = Set.copyOf(allowed);
        this.executor = new RetryExecutor(config, Objects.requireNonNull(transport, "transport"),
                new FailureClassifier(clock), Objects.requireNonNull(breakers, "breakers").gateFor(merchant.id()),
                sleeper, random, ids);
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
        Optional<String> failure = refresh(false);
        Cached now = cache.get();
        if (failure.isPresent() || now == null) {
            throw new DiscoveryFailedException("discovery failed for " + merchant.id() + ": "
                    + failure.orElse("no profile"));
        }
        return now.profile();
    }

    private boolean fresh(Cached c) {
        return clock.instant().isBefore(c.fetchedAt().plus(PROFILE_TTL));
    }

    /** Fetches and checks the profile. Empty on success (the cache holds it); else why not. */
    private Optional<String> refresh(boolean bypassBreaker) {
        ApiCall call = new ApiCall(AttemptOp.GET, "GET", URI.create(base + "/.well-known/ucp"), Optional.empty(),
                Optional.empty(), bypassBreaker);
        return switch (executor.fetch(call)) {
            case RetryExecutor.Fetched.Failed f -> Optional.of(describe(f.outcome()));
            case RetryExecutor.Fetched.Body b -> {
                try {
                    MerchantProfile profile = ProfileParser.parse(merchant.id(), b.reply().body());
                    Optional<String> problem = incompatible(profile);
                    if (problem.isEmpty()) {
                        cache.set(new Cached(profile, clock.instant()));
                    }
                    yield problem;
                } catch (IllegalArgumentException e) {
                    yield Optional.of("invalid profile: " + e.getMessage());
                }
            }
        };
    }

    private Optional<String> incompatible(MerchantProfile p) {
        if (!SUPPORTED_VERSION.equals(p.ucpVersion())) {
            return Optional.of("incompatible UCP version");
        }
        if (!p.capabilities().contains(CHECKOUT_CAPABILITY)) {
            return Optional.of("checkout capability missing");
        }
        if (!allowedHostPorts.contains(hostPort(p.checkoutEndpoint()))
                || !base.getScheme().equalsIgnoreCase(p.checkoutEndpoint().getScheme())) {
            return Optional.of(ENDPOINT_NOT_ALLOWED);
        }
        if (!p.paymentHandlers().contains(PAYMENT_HANDLER)) {
            return Optional.of(HANDLER_MISSING);
        }
        return Optional.empty();
    }

    /** The profile for a call: fresh for a create, any cached one otherwise, fetched if there is none. */
    private Optional<MerchantProfile> profileFor(boolean newIntent, String[] why) {
        Cached cached = cache.get();
        if (cached != null && (!newIntent || fresh(cached))) {
            return Optional.of(cached.profile());
        }
        Optional<String> failure = refresh(!newIntent);
        Cached now = cache.get();
        if (failure.isPresent() || now == null) {
            why[0] = failure.orElse("no profile");
            return Optional.empty();
        }
        return Optional.of(now.profile());
    }

    // ---------------------------------------------------------------- the six calls

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(key, "key");
        return send(AttemptOp.CREATE, "POST", "/checkout-sessions", Optional.empty(), Optional.of(key),
                Optional.of(new FrozenBody(CheckoutWire.createBody(request))), true, false, "");
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(key, "key");
        return send(AttemptOp.UPDATE, "PUT", "/checkout-sessions/", Optional.of(id), Optional.of(key),
                Optional.of(new FrozenBody(CheckoutWire.updateBody(request))), false, false, "");
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(key, "key");
        return send(AttemptOp.COMPLETE, "POST", "/checkout-sessions/", Optional.of(id), Optional.of(key),
                Optional.of(body), false, false, "/complete");
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        return send(AttemptOp.CANCEL, "POST", "/checkout-sessions/", Optional.of(id), Optional.of(key),
                Optional.of(new FrozenBody("{}")), false, false, "/cancel");
    }

    /** The reconciliation read (7.5): no key, no body, and it bypasses the circuit breaker (8.3). */
    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        Objects.requireNonNull(id, "id");
        return send(AttemptOp.GET, "GET", "/checkout-sessions/", Optional.of(id), Optional.empty(), Optional.empty(),
                false, true, "");
    }

    private CallOutcome<CheckoutView> send(
            AttemptOp op, String method, String path, Optional<SessionId> session, Optional<IdempotencyKey> key,
            Optional<FrozenBody> body, boolean newIntent, boolean bypassGate, String suffix) {
        Redactor redactor = new Redactor(body.flatMap(CheckoutWire::paymentToken).stream().toList());
        String[] why = new String[1];
        Optional<MerchantProfile> profile = profileFor(newIntent, why);
        if (profile.isEmpty()) {
            // Nothing was sent to the call's own endpoint, so NotSent is true; a create is never sent without it (K).
            return new CallOutcome.NotSent<>(redactor.scrub("merchant profile unavailable: " + why[0]));
        }
        URI uri = URI.create(profile.get().checkoutEndpoint() + path
                + session.map(s -> segment(s.value())).orElse("") + suffix);
        CallOutcome<CheckoutView> outcome = redactor.scrub(executor.execute(
                new ApiCall(op, method, uri, key, body, bypassGate)));
        if (newIntent && outcome instanceof CallOutcome.ProtocolError<CheckoutView> e && e.httpStatus() >= 400
                && e.httpStatus() < 500 && e.httpStatus() != 429) {
            // Section 20: a 4xx of a create may mean the profile changed; the next create fetches a fresh one. A 429
            // says the merchant is busy, not that the profile changed.
            cache.set(null);
        }
        return session.isPresent() ? checkSession(op, session.get(), outcome) : outcome;
    }

    /** A reply about another session than the one asked for is not trusted (I4). */
    private static CallOutcome<CheckoutView> checkSession(AttemptOp op, SessionId asked, CallOutcome<CheckoutView> outcome) {
        if (outcome instanceof CallOutcome.Ok<CheckoutView> ok && !ok.value().id().equals(asked)) {
            return op == AttemptOp.GET
                    ? new CallOutcome.ProtocolError<>(ok.httpStatus(), "invalid_response",
                            "reply is for a different session", Optional.empty())
                    : new CallOutcome.Uncertain<>("invalid response: reply is for a different session");
        }
        return outcome;
    }

    // ---------------------------------------------------------------- helpers

    private static String describe(CallOutcome<CheckoutView> outcome) {
        return switch (outcome) {
            case CallOutcome.NotSent<CheckoutView> n -> "not sent: " + n.cause();
            case CallOutcome.Uncertain<CheckoutView> u -> "no usable reply: " + u.cause();
            case CallOutcome.ProtocolError<CheckoutView> e -> "http " + e.httpStatus() + " " + e.code();
            case CallOutcome.Rejected<CheckoutView> r -> "unexpected reply";
            case CallOutcome.Ok<CheckoutView> ok -> "unexpected reply";
        };
    }

    private static URI stripTrailingSlash(URI uri) {
        String s = uri.toString();
        return s.endsWith("/") ? URI.create(s.substring(0, s.length() - 1)) : uri;
    }

    /** {@code host:port} in the form {@link #hostPort(URI)} gives, so {@code Host:443} and {@code host:443} compare equal. */
    private static String normalizeHostPort(String text, String scheme) {
        int colon = text.lastIndexOf(':');
        if (colon < 0 && "https".equalsIgnoreCase(scheme) && !text.isEmpty()) {
            return text.toLowerCase(java.util.Locale.ROOT) + ":443";    // https without a port is 443; http is not guessed
        }
        if (colon <= 0 || colon == text.length() - 1) {
            throw new IllegalArgumentException("an endpoint host is host:port, not " + text);
        }
        try {
            return text.substring(0, colon).toLowerCase(java.util.Locale.ROOT) + ":"
                    + Integer.parseInt(text.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("an endpoint host is host:port, not " + text);
        }
    }

    private static String hostPort(URI uri) {
        return uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":"
                + (uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort());
    }

    /** Percent-encodes a path segment: everything except the RFC 3986 unreserved characters. */
    static String segment(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    /** The merchant this gateway is bound to. */
    public MerchantEntry merchant() {
        return merchant;
    }
}
