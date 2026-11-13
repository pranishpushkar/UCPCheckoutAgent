package dev.ucplab.eval;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What a live evaluation has spent so far (C16, m5-plan section 10): each finished run's tokens and estimated cost
 * are added, and once either cap is reached no lane starts another run. Runs already started finish, so the totals may
 * pass a cap by what those runs use; each run is itself held to 6.3's token ceiling. Lock-free.
 */
final class LiveSpend {

    private final LiveModel live;
    private final AtomicLong tokens = new AtomicLong();
    private final AtomicReference<BigDecimal> cost = new AtomicReference<>(BigDecimal.ZERO);

    LiveSpend(LiveModel live) {
        this.live = Objects.requireNonNull(live, "live");
    }

    /** Adds one finished run's input and output tokens, and their estimated cost at the price file's rates. */
    void add(long inputTokens, long outputTokens) {
        BigDecimal runCost = live.prices().cost(inputTokens, outputTokens);
        tokens.addAndGet(inputTokens + outputTokens);
        cost.accumulateAndGet(runCost, BigDecimal::add);
    }

    long tokens() {
        return tokens.get();
    }

    BigDecimal cost() {
        return cost.get();
    }

    /** Why no more runs may start; empty while both caps hold. */
    Optional<String> stopped() {
        if (cost.get().compareTo(live.maxCostUsd()) >= 0) {
            return Optional.of("live cost cap reached: estimated $" + cost.get().toPlainString() + " of --max-cost-usd "
                    + live.maxCostUsd().toPlainString());
        }
        if (tokens.get() >= live.maxTokensTotal()) {
            return Optional.of("live token cap reached: " + tokens.get() + " of --max-tokens-total "
                    + live.maxTokensTotal());
        }
        return Optional.empty();
    }
}
