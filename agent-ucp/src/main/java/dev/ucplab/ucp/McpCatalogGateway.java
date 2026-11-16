package dev.ucplab.ucp;

import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.DiscoveryFailedException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link CatalogGateway} over MCP for the store an {@link McpMerchantGateway} is bound to (FR1, 6.2, 8.4; H5 C6). A search
 * is the read tool {@code search_catalog} with {@code {"catalog": {"query": ...}}}, the arguments the spike recorded, sent
 * through the gateway's profile, token, headers, executor and redactor; the answer's {@code structuredContent} is read as
 * a REST search answer is. The page is cut to the request's limit here: the spike recorded no limit argument.
 *
 * <p>8.4 names no lookup tool, so {@link #lookup} and {@link #product} send nothing and answer as a merchant without the
 * capability does over REST: a {@code ProtocolError} with code {@code capability_missing} and status 0. Results are
 * merchant text and are never cached here (section 20). The client logs nothing.
 */
public final class McpCatalogGateway implements CatalogGateway {

    static final String NO_LOOKUP = "8.4 offers no catalog lookup over MCP";

    private final McpMerchantGateway gateway;

    public McpCatalogGateway(McpMerchantGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public CallOutcome<CatalogPage> search(CatalogSearch request) {
        Objects.requireNonNull(request, "request");
        Optional<CallOutcome<CatalogPage>> refused = refusal();
        if (refused.isPresent()) {
            return refused.get();
        }
        return switch (gateway.read("search_catalog", Map.of("catalog", Map.of("query", request.query())))) {
            case RetryExecutor.Fetched.Failed f -> failure(f.outcome());
            case RetryExecutor.Fetched.Body b -> switch (CatalogParser.parseSearch(b.reply().body())) {
                case CatalogParser.Parsed.Found<CatalogPage> found -> new CallOutcome.Ok<>(b.reply().status(),
                        limited(found.value(), request.limit()), List.of());
                case CatalogParser.Parsed.Error<CatalogPage> e ->
                        new CallOutcome.ProtocolError<>(b.reply().status(), e.code(), e.content(), Optional.empty());
                case CatalogParser.Parsed.Invalid<CatalogPage> i ->
                        new CallOutcome.ProtocolError<>(b.reply().status(), "invalid_response", i.reason(), Optional.empty());
            };
        };
    }

    @Override
    public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("ids must not be empty");
        }
        return new CallOutcome.ProtocolError<>(0, "capability_missing", NO_LOOKUP, Optional.empty());
    }

    @Override
    public CallOutcome<CatalogProduct> product(String id) {
        Objects.requireNonNull(id, "id");
        return new CallOutcome.ProtocolError<>(0, "capability_missing", NO_LOOKUP, Optional.empty());
    }

    /** Why a search cannot be asked, if it cannot: the profile cannot be had, or does not offer search. */
    private Optional<CallOutcome<CatalogPage>> refusal() {
        try {
            if (!gateway.discover(gateway.merchant().id()).capabilities().contains(CatalogClient.SEARCH_CAPABILITY)) {
                return Optional.of(new CallOutcome.ProtocolError<>(0, "capability_missing",
                        "the merchant does not offer " + CatalogClient.SEARCH_CAPABILITY, Optional.empty()));
            }
            return Optional.empty();
        } catch (DiscoveryFailedException e) {
            return Optional.of(new CallOutcome.NotSent<>("merchant profile unavailable: " + e.reason()));
        }
    }

    private static CatalogPage limited(CatalogPage page, int limit) {
        return page.products().size() <= limit ? page
                : new CatalogPage(page.products().subList(0, limit), true);
    }

    private static <T> CallOutcome<T> failure(CallOutcome<CheckoutView> outcome) {
        return switch (outcome) {
            case CallOutcome.NotSent<CheckoutView> n -> new CallOutcome.NotSent<>(n.cause());
            case CallOutcome.Uncertain<CheckoutView> u -> new CallOutcome.Uncertain<>(u.cause());
            case CallOutcome.ProtocolError<CheckoutView> e ->
                    new CallOutcome.ProtocolError<>(e.httpStatus(), e.code(), e.content(), e.retryAfter());
            case CallOutcome.Ok<CheckoutView> ok ->
                    new CallOutcome.ProtocolError<>(ok.httpStatus(), "invalid_response", "unexpected outcome", Optional.empty());
            case CallOutcome.Rejected<CheckoutView> r ->
                    new CallOutcome.ProtocolError<>(r.httpStatus(), "invalid_response", "unexpected outcome", Optional.empty());
        };
    }
}
