package dev.ucplab.eval;

import java.net.URI;
import java.util.Objects;

/**
 * The paths a proxy rule matches, built from the checkout endpoint a merchant's profile declares, never from the literal
 * {@code /ucp/v1} (H1 plan section 5): the lab merchant's endpoint has that prefix, the official sample serves at the origin.
 */
record EndpointPaths(String prefix) {

    /** The lab merchant: its rules are exactly the literals the harness used before H1. */
    static final EndpointPaths LAB = new EndpointPaths("/ucp/v1");

    EndpointPaths {
        Objects.requireNonNull(prefix, "prefix");
    }

    /** The paths under {@code endpoint}'s own path (empty for an endpoint at the origin). */
    static EndpointPaths ofEndpoint(URI endpoint) {
        String path = Objects.requireNonNull(endpoint, "endpoint").getPath();
        String prefix = path == null || path.equals("/") ? "" : path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return new EndpointPaths(prefix);
    }

    String create() {
        return prefix + "/checkout-sessions";
    }

    String complete() {
        return prefix + "/checkout-sessions/*/complete";
    }

    /** Every path under the endpoint. */
    String all() {
        return prefix + "/**";
    }
}
