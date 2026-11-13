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
import java.util.Set;

/**
 * {@code search_catalog} (6.2): {@code query} (1 to 200 characters) and {@code max_results} (1 to 10). Searches each
 * active merchant's catalog and returns {@code {items:[{merchant_id, item_id, title, price_minor, currency,
 * in_stock}]}}, one entry per variant, at most {@code max_results}. {@code title} is wrapped; the merchant's own
 * availability word is left out. A variant whose id a tool would not accept back is left out. When every active
 * merchant's search failed: {@code {"error":"unavailable"}}.
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
        List<Object> items = new ArrayList<>();
        List<MerchantEntry> active = ports.merchants().active();
        int failed = 0;
        for (MerchantEntry merchant : active) {
            CatalogGateway catalog = ports.catalogs().get(merchant.id());
            if (catalog == null || !(catalog.search(new CatalogSearch(query, max)) instanceof CallOutcome.Ok<CatalogPage> ok)) {
                failed++;
                continue;
            }
            for (CatalogProduct product : ok.value().products()) {
                for (CatalogVariant variant : product.variants()) {
                    if (items.size() < max && Args.ID.matcher(variant.itemId()).matches()) {
                        items.add(item(merchant, product, variant));
                    }
                }
            }
        }
        if (!active.isEmpty() && failed == active.size()) {
            return ToolOutcome.error("unavailable");
        }
        return ToolOutcome.ok(Map.of("items", items));
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
