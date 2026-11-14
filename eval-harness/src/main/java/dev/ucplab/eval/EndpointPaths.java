package dev.ucplab.eval;

import java.net.URI;
import java.util.Objects;

/**
 * The paths a proxy rule matches, built from the checkout endpoint a merchant's profile declares, never from the literal
 * {@code /ucp/v1} (H1 plan section 5): the lab merchant's endpoint has that prefix, the official sample serves at the origin.
 */
public record EndpointPaths(String prefix) {

    /** The lab merchant: its rules are exactly the literals the harness used before H1. */
    public static final EndpointPaths LAB = new EndpointPaths("/ucp/v1");

    public EndpointPaths {
        Objects.requireNonNull(prefix, "prefix");
    }

    /** The paths under {@code endpoint}'s own path (empty for an endpoint at the origin). */
    public static EndpointPaths ofEndpoint(URI endpoint) {
        String path = Objects.requireNonNull(endpoint, "endpoint").getPath();
        String prefix = path == null || path.equals("/") ? "" : path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return new EndpointPaths(prefix);
    }

    public String create() {
        return prefix + "/checkout-sessions";
    }

    public String complete() {
        return prefix + "/checkout-sessions/*/complete";
    }

    /** Any one checkout session. */
    public String session() {
        return prefix + "/checkout-sessions/*";
    }

    /** Every path under the endpoint. */
    public String all() {
        return prefix + "/**";
    }
}
