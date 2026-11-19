package dev.ucplab.service.config;

import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.ucp.GlobalCatalogGateway;
import dev.ucplab.ucp.auth.TokenRefresher;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The catalog sources' tokens, fetched when the service starts and again at each one's refresh time (26.7, 20), so no
 * search waits for a token. Each refresh runs on this class's own daemon thread: {@link #start} only schedules, so
 * startup never waits for the token endpoint. A refresh that fails is tried again {@link TokenRefresher#RETRY} later;
 * meanwhile a call fetches its own token, as before. No token, secret or answer body is ever logged (I8).
 */
final class CatalogTokens implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(CatalogTokens.class.getName());

    private final List<TokenRefresher> refreshers;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "catalog-token");
        t.setDaemon(true);
        return t;
    });

    CatalogTokens(List<TokenRefresher> refreshers) {
        this.refreshers = List.copyOf(Objects.requireNonNull(refreshers, "refreshers"));
    }

    /** One refresher for each catalog source that carries a token-tier token; none for the anonymous tier or a store. */
    static CatalogTokens of(Collection<MerchantGateway> gateways, Clock clock) {
        return new CatalogTokens(gateways.stream()
                .filter(GlobalCatalogGateway.class::isInstance).map(GlobalCatalogGateway.class::cast)
                .flatMap(g -> g.tokens().stream()).map(tokens -> new TokenRefresher(tokens, clock)).toList());
    }

    void start() {
        refreshers.forEach(r -> schedule(r, Duration.ZERO));
    }

    private void schedule(TokenRefresher refresher, Duration after) {
        try {
            scheduler.schedule(() -> refresh(refresher), after.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException closed) {
            // Shut down: nothing more to refresh.
        }
    }

    private void refresh(TokenRefresher refresher) {
        Duration next;
        try {
            next = refresher.refresh();
        } catch (RuntimeException e) {
            // The class name only: a message could hold an answer's text (I8).
            LOG.log(System.Logger.Level.WARNING, "catalog token refresh failed: {0}", e.getClass().getSimpleName());
            next = TokenRefresher.RETRY;
        }
        schedule(refresher, next);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
