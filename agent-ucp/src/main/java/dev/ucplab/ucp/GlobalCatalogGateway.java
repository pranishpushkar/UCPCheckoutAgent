package dev.ucplab.ucp;

import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.BuyerCountry;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.LinkOffer;
import dev.ucplab.core.merchant.LinkOffers;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.core.merchant.UpdateCheckout;
import dev.ucplab.ucp.auth.TokenProvider;
import dev.ucplab.ucp.auth.TokenResult;
import dev.ucplab.ucp.mcp.McpEnvelope;
import dev.ucplab.ucp.mcp.McpTransport;
import dev.ucplab.ucp.mcp.StoreHeaders;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shopify's Global Catalog (26.7): a catalog source of many sellers, read only. Every call is one JSON-RPC
 * {@code tools/call} of a read tool ({@code search_catalog}, {@code get_product}) to the entry's {@code url}, its
 * {@code catalog_url}, naming the agent profile in {@code meta}, with the token-tier token when the entry has a
 * {@code credential_ref} and never {@code Shopify-Buyer-IP}. The call goes through the same {@link McpTransport} (which
 * refuses an answer at another {@code ucp.version}), retry executor, breaker and rate limiter as a store's, and a call
 * refused for its token is tried once more with a new one.
 *
 * <p>Every read is for the run's buyer country (26.7, 23.1): its code as {@code context.address_country} and, on a
 * search, {@code filters.ships_to}, its currency as {@code context.currency}; available only, and priced only in that
 * currency ({@link GlobalCatalogParser}). As a
 * {@link MerchantGateway} it takes no checkout call: create, update, complete, cancel and get send nothing and are
 * {@code NotSent} (I12, 26.5, N1), and discovery reads nothing (19: the catalog serves no {@code /.well-known/ucp}).
 * Nothing is cached (20). The gateway logs nothing.
 */
public final class GlobalCatalogGateway implements MerchantGateway, CatalogGateway, LinkOffers {

    /** The Global Catalog's endpoint (26.7); off loopback, a catalog entry's url is exactly this (19). */
    public static final URI SHOPIFY_CATALOG = URI.create("https://catalog.shopify.com/api/ucp/mcp");
    /** The version of Shopify's sample agent profile, at which the catalog answers (26.7). */
    public static final String VERSION = "2026-04-08";
    static final String NO_CHECKOUT = "a catalog source takes no checkout call (I12, 26.7)";
    static final String NO_LOOKUP = "the Global Catalog's lookup is not used (26.7)";
    private static final Pattern AGENT_PROFILE = Pattern.compile("profile=\"([^\"]+)\"");

    private final MerchantEntry catalog;
    private final URI agentProfile;
    private final IdGenerator ids;
    private final Optional<TokenProvider> tokens;
    private final List<String> secrets;
    private final Gate gate;
    private final RetryExecutor tools;

    /**
     * @param tokens the token-tier tokens of the entry's {@code credential_ref} (26.4), or empty for the anonymous tier
     * @param secrets what is scrubbed from every outcome: the credentials behind {@code tokens} (I8)
     */
    public GlobalCatalogGateway(
            MerchantEntry catalog, UcpConfig config, HttpTransport transport, CircuitBreakers breakers, Clock clock,
            Sleeper sleeper, RandomGenerator random, IdGenerator ids, String agentVersion,
            Optional<TokenProvider> tokens, List<String> secrets) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        if (catalog.transport() != Transport.CATALOG) {
            throw new IllegalArgumentException("a Global Catalog gateway is for a transport: catalog entry");
        }
        if (!VERSION.equals(agentVersion)) {
            throw new IllegalArgumentException("the Global Catalog answers at " + VERSION + " (26.7)");
        }
        Matcher profile = AGENT_PROFILE.matcher(Objects.requireNonNull(config, "config").ucpAgent());
        if (!profile.find()) {
            throw new IllegalArgumentException("the UCP-Agent setting names no profile (8.4)");
        }
        this.agentProfile = URI.create(profile.group(1));
        this.ids = Objects.requireNonNull(ids, "ids");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.secrets = List.copyOf(Objects.requireNonNull(secrets, "secrets"));
        this.gate = Objects.requireNonNull(breakers, "breakers").gateFor(catalog.id());
        this.tools = new RetryExecutor(config, new StoreHeaders(new McpTransport(transport, VERSION), tokens,
                Optional.empty()), new FailureClassifier(clock), gate, sleeper, random, ids);
    }

    /**
     * The token-tier tokens this source's calls carry, or empty for the anonymous tier: for the service to fetch the
     * token at startup and keep it ahead of the calls (26.7). The provider never shows the token (I8).
     */
    public Optional<TokenProvider> tokens() {
        return tokens;
    }

    // ---------------------------------------------------------------- the catalog

    @Override
    public CallOutcome<CatalogPage> search(CatalogSearch request) {
        Objects.requireNonNull(request, "request");
        // 6.2, 26.7: the buyer country and its currency; a run's budget, in that currency (23.1), as filters.price.max.
        BuyerCountry country = request.shipTo();
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("available", true);
        filters.put("ships_to", Map.of("country", country.code()));
        request.maxPrice().ifPresent(m -> filters.put("price", Map.of("max", m.minor())));
        String currency = country.currency();
        CallOutcome<CatalogPage> page = call("search_catalog", Map.of("query", request.query(), "context",
                context(country), "filters", filters, "pagination", Map.of("limit", request.limit())),
                body -> GlobalCatalogParser.search(body, currency));
        if (page instanceof CallOutcome.Ok<CatalogPage> ok && ok.value().products().size() > request.limit()) {
            return new CallOutcome.Ok<>(ok.httpStatus(), new CatalogPage(ok.value().products().subList(0,
                    request.limit()), true, ok.value().otherCurrency()), ok.messages());
        }
        return page;
    }

    @Override
    public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("ids must not be empty");
        }
        return new CallOutcome.ProtocolError<>(0, "capability_missing", NO_LOOKUP, Optional.empty());
    }

    /** {@code get_product} of a variant id, for US (26.7). */
    @Override
    public CallOutcome<CatalogProduct> product(String id) {
        return product(id, BuyerCountry.US);
    }

    /** {@code get_product} of a variant id, for the buyer country and in its currency (26.7). */
    @Override
    public CallOutcome<CatalogProduct> product(String id, BuyerCountry shipTo) {
        Objects.requireNonNull(id, "id");
        String currency = shipTo.currency();
        return call("get_product", Map.of("id", id, "context", context(shipTo)),
                body -> GlobalCatalogParser.product(body, currency));
    }

    /** The context of every read: the buyer country and its currency (26.7). */
    private static Map<String, Object> context(BuyerCountry country) {
        return Map.of("address_country", country.code(), "currency", country.currency());
    }

    /**
     * A link intent's offer (7.2 quote_ready (link), 26.7): {@code get_product} of the variant, for the intent's buyer
     * country and in its currency, read fresh each time (20). The catalog's refusal, or an answer without that variant,
     * is {@code Rejected}.
     */
    @Override
    public CallOutcome<LinkOffer> offer(MerchantId merchant, String itemId, BuyerCountry shipTo) {
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(shipTo, "shipTo");
        String currency = shipTo.currency();
        return call("get_product", Map.of("id", itemId, "context", context(shipTo)),
                body -> GlobalCatalogParser.offer(body, itemId, currency), true);
    }

    /** One read tool call, its answer read by {@code parser}. */
    private <T> CallOutcome<T> call(String tool, Map<String, Object> catalogArgs,
                                    Function<byte[], CatalogParser.Parsed<T>> parser) {
        return call(tool, catalogArgs, parser, false);
    }

    /** As above; with {@code refusals}, the catalog's error answer is its refusal ({@code Rejected}), not a fault. */
    private <T> CallOutcome<T> call(String tool, Map<String, Object> catalogArgs,
                                    Function<byte[], CatalogParser.Parsed<T>> parser, boolean refusals) {
        Redactor redactor = new Redactor(secrets);
        Optional<TokenResult> token = tokens.map(TokenProvider::current);
        if (token.isPresent() && token.get() instanceof TokenResult.Failed failed) {
            return new CallOutcome.NotSent<>(redactor.scrub("no access token: " + failed.reason()));
        }
        ApiCall call = new ApiCall(AttemptOp.GET, "POST", catalog.baseUrl(), Optional.empty(),
                Optional.of(McpEnvelope.toolCall(ids, tool, Map.of("catalog", catalogArgs), agentProfile,
                        Optional.empty())), false, Optional.of(VERSION));
        Redactor first = withToken(token);
        RetryExecutor.Fetched fetched = tools.fetch(call);
        if (token.isPresent() && fetched instanceof RetryExecutor.Fetched.Failed f
                && f.outcome() instanceof CallOutcome.ProtocolError<?> e && e.httpStatus() == 401) {
            // A read refused for its token: the token is dropped (StoreHeaders); one more run with a new one (8.2).
            Optional<TokenResult> again = tokens.map(TokenProvider::current);
            fetched = scrubbed(first, scrubbed(withToken(again), tools.fetch(call)));
        }
        return switch (scrubbed(first, fetched)) {
            case RetryExecutor.Fetched.Failed f -> retyped(f.outcome());
            case RetryExecutor.Fetched.Body b -> switch (parser.apply(b.reply().body())) {
                case CatalogParser.Parsed.Found<T> found -> new CallOutcome.Ok<>(b.reply().status(), found.value(),
                        List.of());
                case CatalogParser.Parsed.Error<T> e -> refusals
                        ? new CallOutcome.Rejected<>(b.reply().status(), List.of(new MerchantMessage("error", e.code(),
                                Optional.empty(), e.content(), Optional.empty())))
                        : new CallOutcome.ProtocolError<>(b.reply().status(), e.code(), e.content(), Optional.empty());
                case CatalogParser.Parsed.Invalid<T> i ->
                        new CallOutcome.ProtocolError<>(b.reply().status(), "invalid_response", i.reason(), Optional.empty());
            };
        };
    }

    private static RetryExecutor.Fetched scrubbed(Redactor redactor, RetryExecutor.Fetched fetched) {
        return fetched instanceof RetryExecutor.Fetched.Failed f
                ? new RetryExecutor.Fetched.Failed(redactor.scrub(f.outcome())) : fetched;
    }

    /** The redactor of {@link #secrets}, also masking the token a call carried (I8). */
    private Redactor withToken(Optional<TokenResult> token) {
        List<String> all = new ArrayList<>(secrets);
        if (token.isPresent() && token.get() instanceof TokenResult.Ok ok) {
            all.add(ok.token().authorizationHeader().substring("Bearer ".length()));
        }
        return new Redactor(all);
    }

    private static <T> CallOutcome<T> retyped(CallOutcome<CheckoutView> outcome) {
        return switch (outcome) {
            case CallOutcome.NotSent<CheckoutView> n -> new CallOutcome.NotSent<>(n.cause());
            case CallOutcome.Uncertain<CheckoutView> u -> new CallOutcome.Uncertain<>(u.cause());
            case CallOutcome.ProtocolError<CheckoutView> e ->
                    new CallOutcome.ProtocolError<>(e.httpStatus(), e.code(), e.content(), e.retryAfter());
            case CallOutcome.Ok<CheckoutView> ok ->
                    new CallOutcome.ProtocolError<>(ok.httpStatus(), "invalid_response", "unexpected outcome", Optional.empty());
            case CallOutcome.Rejected<CheckoutView> r -> new CallOutcome.Rejected<>(r.httpStatus(), r.messages());
        };
    }

    // ---------------------------------------------------------------- no checkout (I12, 26.7)

    /** No I/O (19): the catalog serves no profile; this one offers search and lookup at {@link #VERSION}. */
    @Override
    public MerchantProfile discover(MerchantId merchant) {
        Objects.requireNonNull(merchant, "merchant");
        return new MerchantProfile(merchant, VERSION, catalog.baseUrl(),
                Set.of(CatalogClient.SEARCH_CAPABILITY, "dev.ucp.shopping.catalog.lookup"), Set.of());
    }

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        return new CallOutcome.NotSent<>(NO_CHECKOUT);
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        return new CallOutcome.NotSent<>(NO_CHECKOUT);
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        return new CallOutcome.NotSent<>(NO_CHECKOUT);
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        return new CallOutcome.NotSent<>(NO_CHECKOUT);
    }

    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        return new CallOutcome.NotSent<>(NO_CHECKOUT);
    }

    /** 8.3: false while the catalog's breaker is open or its limiter is blocked by a Retry-After. */
    @Override
    public boolean admitsCalls() {
        return gate.admitting();
    }

    /** The catalog entry this gateway reads. */
    public MerchantEntry merchant() {
        return catalog;
    }

    @Override
    public String toString() {
        return "GlobalCatalogGateway[" + catalog.id().value() + "]";
    }
}
