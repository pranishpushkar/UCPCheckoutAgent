package dev.ucplab.runtime;

import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.MerchantEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code search_catalog} (6.2): {@code query} (1 to 200 characters) and {@code max_results} (1 to 10). Searches each
 * active merchant's catalog and returns {@code {items:[{merchant_id, item_id, title, price_minor, currency,
 * in_stock}]}}, one entry per variant, at most {@code max_results}. {@code title} is wrapped; the merchant's own
 * availability word is left out. A variant whose id a tool would not accept back is left out. Every active merchant is
 * asked at once, for {@link ToolPorts#perMerchant()} at most (19); the items are merged round-robin in registry order. A
 * merchant that timed out or failed is left out and named in {@code unavailable}; when every active merchant failed:
 * {@code {"error":"unavailable"}}.
 */
final class SearchCatalogTool implements Tool {

    static final String NAME = "search_catalog";
    private static final ToolSpec SPEC = new ToolSpec(NAME,
            "Searches the merchants' catalogs. Returns matching items with their prices in minor units.",
            CanonicalJson.write(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string", "minLength", 1, "maxLength", 200,
                                    "description", "What to search for."),
                            "max_results", Map.of("type", "integer", "minimum", 1, "maximum", CatalogSearch.MAX_LIMIT,
                                    "description", "The most items to return.")),
                    "required", List.of("query", "max_results"),
                    "additionalProperties", false)));

    private final ToolPorts ports;

    SearchCatalogTool(ToolPorts ports) {
        this.ports = Objects.requireNonNull(ports, "ports");
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolOutcome run(ToolContext context, Map<String, Object> arguments) {
        String query;
        int max;
        try {
            Args.only(arguments, Set.of("query", "max_results"));
            query = Args.text(arguments, "query", 1, 200);
            max = Args.integer(arguments.get("max_results"), "max_results", 1, CatalogSearch.MAX_LIMIT);
        } catch (Args.Invalid e) {
            return ToolOutcome.invalid(e.getMessage());
        }
        List<MerchantEntry> active = ports.merchants().active();
        List<Optional<CatalogPage>> pages = askAll(active, new CatalogSearch(query, max));
        List<List<Object>> perMerchant = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            MerchantEntry merchant = active.get(i);
            if (pages.get(i).isEmpty()) {
                unavailable.add(merchant.id().value());
                continue;
            }
            List<Object> mine = new ArrayList<>();
            for (CatalogProduct product : pages.get(i).get().products()) {
                for (CatalogVariant variant : product.variants()) {
                    if (Args.ID.matcher(variant.itemId()).matches()) {
                        mine.add(item(merchant, product, variant));
                    }
                }
            }
            perMerchant.add(mine);
        }
        if (!active.isEmpty() && unavailable.size() == active.size()) {
            return ToolOutcome.error("unavailable");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", roundRobin(perMerchant, max));
        if (!unavailable.isEmpty()) {
            result.put("unavailable", unavailable);
        }
        return ToolOutcome.ok(result);
    }

    /**
     * Asks every merchant at once, each on a virtual thread, and waits for all of them until one limit has passed (the
     * deadline is shared, so the search takes at most {@code perMerchant}, never the sum). A merchant that is not done by
     * then is cancelled with an interrupt, which ends its HTTP call (the transport turns it into one failed attempt, and
     * the retry loop does not try again), and counts as failed, like one that answered with anything but a page. A catalog
     * that throws (a bug: failures are outcomes) fails the tool, as before.
     * The answer for each merchant is in {@code active}'s order, whatever order they finished in. Nothing here holds a
     * lock; the pool is shut down without waiting, so a call that ignores the interrupt cannot hold the search.
     */
    private List<Optional<CatalogPage>> askAll(List<MerchantEntry> active, CatalogSearch request) {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<CallOutcome<CatalogPage>>> futures = new ArrayList<>();
            for (MerchantEntry merchant : active) {
                CatalogGateway catalog = ports.catalogs().get(merchant.id());
                futures.add(catalog == null ? null : pool.submit(() -> catalog.search(request)));
            }
            long deadline = System.nanoTime() + ports.perMerchant().toNanos();
            List<Optional<CatalogPage>> pages = new ArrayList<>();
            for (Future<CallOutcome<CatalogPage>> future : futures) {
                pages.add(future == null ? Optional.empty() : await(future, deadline));
            }
            return pages;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Optional<CatalogPage> await(Future<CallOutcome<CatalogPage>> future, long deadline) {
        try {
            CallOutcome<CatalogPage> outcome = future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return outcome instanceof CallOutcome.Ok<CatalogPage> ok ? Optional.of(ok.value()) : Optional.empty();
        } catch (TimeoutException e) {
            future.cancel(true);
            return Optional.empty();
        } catch (ExecutionException e) {
            // A catalog reports failure as an outcome; an exception is a bug, and it fails the tool as it always did.
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Optional.empty();
        }
    }

    /** One item from each merchant in turn, in registry order, each merchant's own order kept, up to {@code max}. */
    private static List<Object> roundRobin(List<List<Object>> perMerchant, int max) {
        List<Object> out = new ArrayList<>();
        for (int round = 0; out.size() < max; round++) {
            boolean any = false;
            for (List<Object> mine : perMerchant) {
                if (round < mine.size() && out.size() < max) {
                    out.add(mine.get(round));
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
        return out;
    }

    private static Map<String, Object> item(MerchantEntry merchant, CatalogProduct product, CatalogVariant variant) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("merchant_id", merchant.id().value());
        item.put("item_id", variant.itemId());
        item.put("title", ToolText.title(product, variant));
        item.put("price_minor", variant.price().minor());
        item.put("currency", variant.price().currency());
        item.put("in_stock", variant.availability().available());
        return item;
    }
}
