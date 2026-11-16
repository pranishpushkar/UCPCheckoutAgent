package dev.ucplab.ucp.mcp;

import dev.ucplab.ucp.HttpCall;
import dev.ucplab.ucp.HttpReply;
import dev.ucplab.ucp.HttpTransport;
import dev.ucplab.ucp.auth.AccessToken;
import dev.ucplab.ucp.auth.TokenProvider;
import dev.ucplab.ucp.auth.TokenResult;
import dev.ucplab.ucp.registry.BuyerIp;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The headers a store's calls carry (8.4, 26.4), put on each attempt: {@code Authorization} with the merchant's current
 * access token when there is a {@link TokenProvider}, and {@code Shopify-Buyer-IP} when the registry entry asks for it.
 * Neither value is logged or put in an exception (I8): {@link HttpCall#toString()} shows no header.
 *
 * <p>A 401 (an HTTP 401, or a JSON-RPC {@code AuthenticationFailed} that {@link McpTransport} below has mapped to one)
 * drops the token that attempt carried, so the next caller fetches a new one. Nothing is resent here: whether the call is
 * repeated is the gateway's decision under 8.2. When no token can be had the attempt is not sent and an
 * {@link IOException} with the provider's fixed reason is thrown; the gateway asks for a token before a call, so this is
 * reached only if a token runs out between attempts.
 */
public final class StoreHeaders implements HttpTransport {

    public static final String BUYER_IP_HEADER = "Shopify-Buyer-IP";

    /** No token for an attempt: the provider's fixed reason, never a value (I8). */
    static final class NoToken extends IOException {
        NoToken(String reason) {
            super("no access token: " + reason);
        }
    }

    private final HttpTransport next;
    private final Optional<TokenProvider> tokens;
    private final Optional<BuyerIp> buyerIp;

    public StoreHeaders(HttpTransport next, Optional<TokenProvider> tokens, Optional<BuyerIp> buyerIp) {
        this.next = Objects.requireNonNull(next, "next");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.buyerIp = Objects.requireNonNull(buyerIp, "buyerIp");
    }

    @Override
    public HttpReply send(HttpCall call) throws IOException {
        Objects.requireNonNull(call, "call");
        Map<String, String> headers = new LinkedHashMap<>(call.headers());
        Optional<AccessToken> used = Optional.empty();
        if (tokens.isPresent()) {
            TokenResult result = tokens.get().current();
            if (result instanceof TokenResult.Failed failed) {
                throw new NoToken(failed.reason());
            }
            AccessToken token = ((TokenResult.Ok) result).token();
            headers.put("Authorization", token.authorizationHeader());
            used = Optional.of(token);
        }
        if (buyerIp.isPresent()) {
            headers.put(BUYER_IP_HEADER, buyerIp.get().value());
        }
        HttpReply reply = next.send(new HttpCall(call.method(), call.uri(), headers, call.body(), call.timeout()));
        if (reply.status() == 401 && used.isPresent()) {
            tokens.get().invalidate(used.get());
        }
        return reply;
    }

    @Override
    public String toString() {
        return "StoreHeaders[token=" + tokens.isPresent() + ", buyerIp=" + buyerIp.isPresent() + "]";
    }
}
