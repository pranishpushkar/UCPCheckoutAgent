package dev.ucplab.eval;

import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.eval.report.PriceTable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Live mode (C16, m5-plan section 10): how each agent run gets its model, and the evaluation's spending caps. It never
 * holds the key: {@code clients} closes over a {@code ModelKey}, whose {@code toString} is redacted.
 *
 * @param model the model id, recorded in every run record and in the report; the price file must be for it
 * @param promptVersion the system prompt's version, for the report
 * @param prices the price file the run's estimated cost is taken from
 * @param maxCostUsd once the evaluation's estimated cost reaches it, no more runs start
 * @param maxTokensTotal once the evaluation's input plus output tokens reach it, no more runs start
 * @param clients a new client for one run, given the observer of the function names in each request the client
 *     builds (the live I6 check). A client that is {@link AutoCloseable} is closed when its run ends
 */
public record LiveModel(String model, String promptVersion, PriceTable prices, BigDecimal maxCostUsd,
                        long maxTokensTotal, Function<Consumer<List<String>>, ModelClient> clients) {

    /** The caps of every live command (m5-plan section 10, Decision 3): $5 and 2,000,000 tokens. */
    public static final BigDecimal MAX_COST_USD = new BigDecimal("5");
    public static final long MAX_TOKENS_TOTAL = 2_000_000;

    public LiveModel {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(prices, "prices");
        Objects.requireNonNull(maxCostUsd, "maxCostUsd");
        Objects.requireNonNull(clients, "clients");
        if (!prices.model().equals(model)) {
            throw new IllegalArgumentException("the price file is for " + prices.model() + ", not " + model);
        }
        if (!prices.currency().equals("USD")) {
            throw new IllegalArgumentException("the price file must be in USD for --max-cost-usd");
        }
        if (maxCostUsd.signum() <= 0 || maxCostUsd.compareTo(MAX_COST_USD) > 0) {
            throw new IllegalArgumentException("--max-cost-usd must be above 0 and at most " + MAX_COST_USD);
        }
        if (maxTokensTotal <= 0 || maxTokensTotal > MAX_TOKENS_TOTAL) {
            throw new IllegalArgumentException("--max-tokens-total must be 1 to " + MAX_TOKENS_TOTAL);
        }
    }
}
