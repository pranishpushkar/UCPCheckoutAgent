package dev.ucplab.runtime;

import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.Money;
import dev.ucplab.core.money.MoneyFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code search_catalog} (6.2): {@code query} (1 to 200 characters) and {@code max_results} (1 to 10). Searches each
 * active merchant's catalog and returns {@code {items:[{merchant_id, item_id, title, price_minor, price, currency,
 * in_stock, options?, seller?}]}}, one entry per variant, at most {@code max_results}. {@code title} is wrapped, and so
 * are {@code seller}, a catalog source's seller name (26.7), and each name and label of {@code options}, the variant's
 * own; the merchant's own
 * availability word is left out. A variant whose id a tool would not accept back is left out. Every active merchant is
 * asked at once, for {@link ToolPorts#perMerchant()} at most (19); the items are merged round-robin in registry order. A
 * merchant that timed out or failed is left out and named in {@code unavailable}; when every active merchant failed:
 * {@code {"error":"unavailable"}}. With a run budget (23.1) the search carries it as its price limit, from the run and
 * never from the model, and an item in another currency or priced above it is left out whatever the merchant returned:
 * a price filter is a request, not a guarantee. Items left out for their price are named only as {@code above_budget
 * {count, lowest_price_minor, lowest_price, currency}}; when nothing fits, the same merchants are asked once more without the filter
 * for it, the only second search.
 */
final class SearchCatalogTool implements Tool {

    /** 6.2: the most items a catalog source (26.7) is asked for and contributes to one search. */
    static final int CATALOG_MAX_RESULTS = 5;


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
        Optional<Money> limit = context.budget().map(b -> new Money(b.amountMinor(), b.currency()));
        // 26.7, 23.1: the run's buyer country, from its context: no argument can name one.
        List<Optional<CatalogPage>> pages = askAll(active, new CatalogSearch(query, max, limit, context.shipTo()));
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
                    if (Args.ITEM_ID.matcher(variant.itemId()).matches() && fits(variant, limit)) {
                        mine.add(item(merchant, product, variant));
                    }
                }
            }
            perMerchant.add(mine);
        }
        if (!active.isEmpty() && unavailable.size() == active.size()) {
            return ToolOutcome.error("unavailable");
        }
        List<Object> items = roundRobin(perMerchant, max);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        if (limit.isPresent()) {
            // 6.2, the near miss: a merchant that honours the filter returns nothing above it, so when nothing fits the
            // same search is asked once more without it. Only numbers from the catalog reach the model.
            List<Optional<CatalogPage>> read = items.isEmpty()
                    ? askAll(active, new CatalogSearch(query, max, Optional.empty(), context.shipTo())) : pages;
            aboveBudget(read, limit.get()).ifPresent(a -> result.put("above_budget", a));
        }
        // 6.2, 26.7: the country searched for, our own text; and how many items the first search left out for a price
        // in another currency, a number only (the near miss's second search would count them twice).
        result.put("ships_to", Map.of("country", context.shipTo().code(), "country_name", context.shipTo().name()));
        long other = pages.stream().flatMap(Optional::stream).mapToLong(CatalogPage::otherCurrency).sum();
        if (other > 0) {
            result.put("other_currency", Map.of("count", other));
        }
        if (!unavailable.isEmpty()) {
            result.put("unavailable", unavailable);
        }
        return ToolOutcome.ok(result);
    }

    /** The page of each merchant in {@code active}'s order; empty if it timed out, failed or answered with anything but a page. */
    private List<Optional<CatalogPage>> askAll(List<MerchantEntry> active, CatalogSearch request) {
        return FanOut.ask(ports, active, c -> c.search(request)).stream()
                .map(answer -> answer.flatMap(o -> o instanceof CallOutcome.Ok<CatalogPage> ok
                        ? Optional.of(ok.value()) : Optional.<CatalogPage>empty()))
                .toList();
    }

    /**
     * {@code {count, lowest_price_minor, currency}} of the variants in {@code pages} in the limit's currency and priced
     * above it, or empty when there are none (6.2). Numbers only: nothing else of a left-out variant.
     */
    private static Optional<Map<String, Object>> aboveBudget(List<Optional<CatalogPage>> pages, Money limit) {
        long count = 0;
        long lowest = Long.MAX_VALUE;
        for (Optional<CatalogPage> page : pages) {
            for (CatalogProduct product : page.map(CatalogPage::products).orElse(List.of())) {
                for (CatalogVariant variant : product.variants()) {
                    Money price = variant.price();
                    if (Args.ITEM_ID.matcher(variant.itemId()).matches() && price.currency().equals(limit.currency())
                            && price.minor() > limit.minor()) {
                        count++;
                        lowest = Math.min(lowest, price.minor());
                    }
                }
            }
        }
        if (count == 0) {
            return Optional.empty();
        }
        Map<String, Object> above = new LinkedHashMap<>();
        above.put("count", count);
        above.put("lowest_price_minor", lowest);
        above.put("lowest_price", MoneyFormat.format(lowest, limit.currency()));
        above.put("currency", limit.currency());
        return Optional.of(above);
    }

    /** True without a limit, or when the variant is in the limit's currency and priced at or below it (6.2). */
    private static boolean fits(CatalogVariant variant, Optional<Money> limit) {
        return limit.isEmpty() || variant.price().currency().equals(limit.get().currency())
                && variant.price().minor() <= limit.get().minor();
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
        // 6.2 amounts as text: the price as the agent writes it, formatted from the minor units (24.3).
        item.put("price", MoneyFormat.format(variant.price().minor(), variant.price().currency()));
        item.put("currency", variant.price().currency());
        item.put("in_stock", variant.availability().available());
        if (!variant.options().isEmpty()) {
            item.put("options", ToolText.options(variant));
        }
        // 6.2, 26.7: a catalog source's seller, by name only, wrapped like the title; never its domain or a URL (I6).
        variant.seller().ifPresent(s -> item.put("seller", ToolText.seller(s)));
        return item;
    }
}
