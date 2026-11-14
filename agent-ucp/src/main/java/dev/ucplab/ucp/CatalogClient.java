package dev.ucplab.ucp;

import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.DiscoveryFailedException;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.MerchantProfile;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * {@link CatalogGateway} over HTTP for the merchant a {@link UcpHttpGateway} is bound to (FR1, 6.2, 8.2).
 *
 * <p>Catalog calls are reads. They go through {@link RetryExecutor} as a {@code get} does: no {@code Idempotency-Key},
 * {@code NotSent} and {@code Uncertain} retried up to 3 times, 429 and 503 retried honouring {@code Retry-After}, and
 * the shared circuit breaker of the merchant's host and port applies. A POST body is the only difference from a get.
 * A 200 body the client cannot read is a {@code ProtocolError} ({@code invalid_response}), as for {@code get}; an
 * {@code Uncertain} outcome only means the retries ran out without an answer.
 *
 * <p>The endpoint comes from the merchant's cached discovery profile, which must offer
 * {@code dev.ucp.shopping.catalog.search} for a search and {@code dev.ucp.shopping.catalog.lookup} for a lookup or a
 * product. If the profile is not available nothing is sent ({@code NotSent}); if the capability is missing the answer is
 * a {@code ProtocolError} with code {@code capability_missing} and status 0 (no HTTP exchange took place). Results are
 * not cached here (section 20: the tool layer does that, with a TTL). The client logs nothing.
 */
public final class CatalogClient implements CatalogGateway {

    public static final String SEARCH_CAPABILITY = "dev.ucp.shopping.catalog.search";
    public static final String LOOKUP_CAPABILITY = "dev.ucp.shopping.catalog.lookup";

    private final UcpHttpGateway gateway;
    private final RetryExecutor executor;
    private final Redactor redactor = new Redactor(List.of());

    public CatalogClient(
            UcpHttpGateway gateway, UcpConfig config, HttpTransport transport, CircuitBreakers breakers, Clock clock,
            Sleeper sleeper, RandomGenerator random, IdGenerator ids) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(breakers, "breakers");
        this.executor = new RetryExecutor(Objects.requireNonNull(config, "config"),
                Objects.requireNonNull(transport, "transport"), new FailureClassifier(Objects.requireNonNull(clock, "clock")),
                breakers.gateFor(gateway.merchant().id()), sleeper, random, ids);
    }

    @Override
    public CallOutcome<CatalogPage> search(CatalogSearch request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", request.query());
        body.put("pagination", Map.of("limit", (long) request.limit()));
        return call(SEARCH_CAPABILITY, "/catalog/search", body, CatalogParser::parseSearch);
    }

    @Override
    public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("ids must not be empty");
        }
        return call(LOOKUP_CAPABILITY, "/catalog/lookup", Map.of("ids", List.copyOf(ids)), CatalogParser::parseLookup);
    }

    @Override
    public CallOutcome<CatalogProduct> product(String id) {
        Objects.requireNonNull(id, "id");
        return call(LOOKUP_CAPABILITY, "/catalog/product", Map.of("id", id), CatalogParser::parseProduct);
    }

    private interface Parse<T> {
        CatalogParser.Parsed<T> parse(byte[] body);
    }

    private <T> CallOutcome<T> call(String capability, String path, Map<String, Object> body, Parse<T> parse) {
        MerchantProfile profile;
        try {
            profile = gateway.discover(gateway.merchant().id());
        } catch (DiscoveryFailedException e) {
            return new CallOutcome.NotSent<>(redactor.scrub("merchant profile unavailable: " + e.getMessage()));
        }
        if (!profile.capabilities().contains(capability)) {
            return new CallOutcome.ProtocolError<>(0, "capability_missing",
                    "the merchant does not offer " + capability, Optional.empty());
        }
        URI uri = URI.create(profile.checkoutEndpoint() + path);
        ApiCall call = new ApiCall(AttemptOp.GET, "POST", uri, Optional.empty(),
                Optional.of(new FrozenBody(CanonicalJson.write(body))), false);
        return switch (executor.fetch(call)) {
            case RetryExecutor.Fetched.Failed f -> failure(redactor.scrub(f.outcome()));
            case RetryExecutor.Fetched.Body b -> switch (parse.parse(b.reply().body())) {
                case CatalogParser.Parsed.Found<T> found ->
                        new CallOutcome.Ok<>(b.reply().status(), found.value(), List.of());
                case CatalogParser.Parsed.Error<T> e ->
                        new CallOutcome.ProtocolError<>(b.reply().status(), e.code(), e.content(), Optional.empty());
                case CatalogParser.Parsed.Invalid<T> i ->
                        new CallOutcome.ProtocolError<>(b.reply().status(), "invalid_response", i.reason(), Optional.empty());
            };
        };
    }

    /** The executor's failure outcome, for a call whose value is not a checkout. */
    private static <T> CallOutcome<T> failure(CallOutcome<CheckoutView> outcome) {
        return switch (outcome) {
            case CallOutcome.NotSent<CheckoutView> n -> new CallOutcome.NotSent<>(n.cause());
            case CallOutcome.Uncertain<CheckoutView> u -> new CallOutcome.Uncertain<>(u.cause());
            case CallOutcome.ProtocolError<CheckoutView> e ->
                    new CallOutcome.ProtocolError<>(e.httpStatus(), e.code(), e.content(), e.retryAfter());
            // A raw fetch returns every 200 and 201 as a body, so these cannot occur; never pass one on as a result.
            case CallOutcome.Ok<CheckoutView> ok ->
                    new CallOutcome.ProtocolError<>(ok.httpStatus(), "invalid_response", "unexpected outcome", Optional.empty());
            case CallOutcome.Rejected<CheckoutView> r ->
                    new CallOutcome.ProtocolError<>(r.httpStatus(), "invalid_response", "unexpected outcome", Optional.empty());
        };
    }
}
