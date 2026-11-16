package dev.ucplab.ucp.auth;

import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.ucp.HttpCall;
import dev.ucplab.ucp.HttpReply;
import dev.ucplab.ucp.HttpTransport;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A merchant's access token (20, 26.4): {@code client_credentials} against the token endpoint, held in memory with its
 * expiry, refreshed before it ends, never written or logged (I8).
 *
 * <p><b>One refresh at a time, no lock.</b> The state is one {@link AtomicReference}. The caller that wins a
 * compare-and-set fetches; while it does, the others use the current token if it has not ended yet, or wait on the
 * refresh's future. No lock or monitor is taken anywhere, so none is held across the network call (7.8).
 *
 * <p><b>Lifetime (VERIFY 1).</b> The answer's {@code expires_in} if it carries one, else the configured default. A
 * token is refreshed once {@code refreshMargin} before it ends (at most half its lifetime).
 *
 * <p><b>A 401 from the store.</b> {@link #invalidate} drops the token the store refused, so the next caller fetches a
 * new one. It never resends anything: whether to repeat the store call is the gateway's decision under 8.2 (a read once;
 * a mutating call never, since 8.2 does not retry a 401).
 */
public final class TokenProvider {

    static final String UNREACHABLE = "token endpoint unreachable";
    static final String BAD_ANSWER = "token endpoint answer has no access_token";
    static final String INTERRUPTED = "interrupted while waiting for a token";

    private record State(Optional<AccessToken> token, Optional<CompletableFuture<TokenResult>> refreshing) {}

    private final URI endpoint;
    private final ClientCredentials credentials;
    private final HttpTransport transport;
    private final Clock clock;
    private final Duration defaultLifetime;
    private final Duration refreshMargin;
    private final Duration timeout;
    private final AtomicReference<State> state = new AtomicReference<>(new State(Optional.empty(), Optional.empty()));

    /**
     * @param defaultLifetime the lifetime assumed when the answer carries no {@code expires_in} (VERIFY 1)
     * @param refreshMargin how long before its end a token is refreshed
     * @param timeout the token call's request timeout
     */
    public TokenProvider(
            URI endpoint, ClientCredentials credentials, HttpTransport transport, Clock clock, Duration defaultLifetime,
            Duration refreshMargin, Duration timeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defaultLifetime = positive(defaultLifetime, "defaultLifetime");
        this.refreshMargin = Objects.requireNonNull(refreshMargin, "refreshMargin");
        this.timeout = positive(timeout, "timeout");
        if (refreshMargin.isNegative()) {
            throw new IllegalArgumentException("refreshMargin must not be negative");
        }
    }

    /** A usable token: the one held, or a new one. Waits only for a refresh another caller started. */
    public TokenResult current() {
        while (true) {
            State s = state.get();
            Instant now = clock.instant();
            if (s.token().isPresent() && now.isBefore(s.token().get().refreshAt())) {
                return new TokenResult.Ok(s.token().get());
            }
            if (s.refreshing().isPresent()) {
                if (s.token().isPresent() && now.isBefore(s.token().get().expiresAt())) {
                    return new TokenResult.Ok(s.token().get());   // due, not ended: use it while another refreshes
                }
                return await(s.refreshing().get());
            }
            CompletableFuture<TokenResult> refresh = new CompletableFuture<>();
            if (state.compareAndSet(s, new State(s.token(), Optional.of(refresh)))) {
                TokenResult result = fetch();
                Optional<AccessToken> kept = result instanceof TokenResult.Ok ok ? Optional.of(ok.token())
                        : s.token().filter(t -> clock.instant().isBefore(t.expiresAt()));
                state.set(new State(kept, Optional.empty()));
                TokenResult answer = result instanceof TokenResult.Failed && kept.isPresent()
                        ? new TokenResult.Ok(kept.get()) : result;
                refresh.complete(answer);
                return answer;
            }
        }
    }

    /**
     * The store refused {@code rejected} (a 401): drop it, so the next {@link #current} fetches a new token. A token
     * already replaced, or a refresh already running, is left alone.
     */
    public void invalidate(AccessToken rejected) {
        Objects.requireNonNull(rejected, "rejected");
        State s = state.get();
        if (s.refreshing().isEmpty() && s.token().isPresent() && s.token().get() == rejected) {
            state.compareAndSet(s, new State(Optional.empty(), Optional.empty()));
        }
    }

    private static TokenResult await(CompletableFuture<TokenResult> refresh) {
        try {
            return refresh.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TokenResult.Failed(INTERRUPTED);
        } catch (ExecutionException e) {
            return new TokenResult.Failed(UNREACHABLE);
        }
    }

    private TokenResult fetch() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", credentials.clientId());
        body.put("client_secret", credentials.clientSecret());
        body.put("grant_type", "client_credentials");
        HttpCall call = new HttpCall("POST", endpoint,
                Map.of("Content-Type", "application/json", "Accept", "application/json"),
                Optional.of(new FrozenBody(CanonicalJson.write(body))), timeout);
        Instant sent = clock.instant();
        HttpReply reply;
        try {
            reply = transport.send(call);
        } catch (IOException | RuntimeException e) {
            return new TokenResult.Failed(UNREACHABLE);
        }
        if (reply.status() != 200) {
            return new TokenResult.Failed("token endpoint http " + reply.status());
        }
        Map<String, Object> answer;
        try {
            answer = LenientJson.readObject(new String(reply.body(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException notJson) {
            return new TokenResult.Failed(BAD_ANSWER);
        }
        if (!(answer.get("access_token") instanceof String token) || token.isBlank()) {
            return new TokenResult.Failed(BAD_ANSWER);
        }
        // TODO(VERIFY 1): the spike did not record whether Shopify's answer carries expires_in; until a live run says,
        // a token without one is assumed to last the configured default.
        Duration lifetime = answer.get("expires_in") instanceof Long seconds && seconds > 0
                ? Duration.ofSeconds(seconds) : defaultLifetime;
        Duration margin = refreshMargin.compareTo(lifetime.dividedBy(2)) > 0 ? lifetime.dividedBy(2) : refreshMargin;
        Instant expiresAt = sent.plus(lifetime);
        return new TokenResult.Ok(new AccessToken(token, expiresAt, expiresAt.minus(margin)));
    }

    private static Duration positive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return d;
    }

    @Override
    public String toString() {
        return "TokenProvider[" + endpoint.getHost() + "]";
    }
}
