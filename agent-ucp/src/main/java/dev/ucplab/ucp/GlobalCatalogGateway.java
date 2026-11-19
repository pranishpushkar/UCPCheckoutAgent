package dev.ucplab.ucp;

import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import dev.ucplab.ucp.auth.TokenProvider;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

/** Skeleton for the red tests of real catalog stage 1 (26.7): nothing is built yet. */
public final class GlobalCatalogGateway implements MerchantGateway, CatalogGateway {

    private static final String NOT_BUILT = "not built yet";
    private final MerchantEntry catalog;

    public GlobalCatalogGateway(
            MerchantEntry catalog, UcpConfig config, HttpTransport transport, CircuitBreakers breakers, Clock clock,
            Sleeper sleeper, RandomGenerator random, IdGenerator ids, String agentVersion,
            Optional<TokenProvider> tokens, List<String> secrets) {
        this.catalog = catalog;
    }

    @Override
    public MerchantProfile discover(MerchantId merchant) {
        return new MerchantProfile(merchant, "2026-04-08", catalog.baseUrl(), Set.of(), Set.of());
    }

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        return new CallOutcome.NotSent<>(NOT_BUILT);
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        return new CallOutcome.NotSent<>(NOT_BUILT);
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        return new CallOutcome.NotSent<>(NOT_BUILT);
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        return new CallOutcome.NotSent<>(NOT_BUILT);
    }

    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        return new CallOutcome.NotSent<>(NOT_BUILT);
    }

    @Override
    public CallOutcome<CatalogPage> search(CatalogSearch request) {
        return new CallOutcome.NotSent<>(NOT_BUILT);
    }

    @Override
    public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
        return new CallOutcome.NotSent<>(NOT_BUILT);
    }

    @Override
    public CallOutcome<CatalogProduct> product(String id) {
        return new CallOutcome.NotSent<>(NOT_BUILT);
    }
}
