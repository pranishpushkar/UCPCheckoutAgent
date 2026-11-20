package dev.ucplab.runtime;

import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.MerchantEntry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code get_product} (6.2): {@code item_id}. Asks every active merchant's catalog at once, through the same limit as
 * {@code search_catalog} ({@link FanOut}), and returns the item of the lowest registry index that has it:
 * {@code {merchant_id, item_id, title, description, price_minor, currency, in_stock, options?}} for the variant with that
 * id (the featured variant when the id is a product's). {@code title}, {@code description} and the variant's own
 * {@code options} are wrapped; a product without a
 * description has an empty one. No merchant knows the id: {@code {"error":"not_found"}}; a merchant could not be
 * asked and none had it: {@code {"error":"unavailable"}}.
 */
final class GetProductTool implements Tool {

    static final String NAME = "get_product";
    private static final ToolSpec SPEC = new ToolSpec(NAME,
            "Returns one item with its description, price in minor units and stock.",
            CanonicalJson.write(Map.of(
                    "type", "object",
                    "properties", Map.of("item_id", Map.of("type", "string", "pattern", Args.ITEM_ID_SCHEMA,
                            "description", "The item_id from search_catalog.")),
                    "required", List.of("item_id"),
                    "additionalProperties", false)));

    private final ToolPorts ports;

    GetProductTool(ToolPorts ports) {
        this.ports = Objects.requireNonNull(ports, "ports");
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolOutcome run(ToolContext context, Map<String, Object> arguments) {
        String itemId;
        try {
            Args.only(arguments, Set.of("item_id"));
            itemId = Args.itemId(arguments, "item_id");
        } catch (Args.Invalid e) {
            return ToolOutcome.invalid(e.getMessage());
        }
        List<MerchantEntry> active = ports.merchants().active();
        List<Optional<CallOutcome<CatalogProduct>>> answers = FanOut.ask(ports, active,
                c -> c.product(itemId, context.shipTo()));
        boolean unavailable = false;
        // Registry order: the first merchant that has the item answers, whatever order they finished in (decision 5).
        for (int i = 0; i < active.size(); i++) {
            CallOutcome<CatalogProduct> outcome = answers.get(i).orElse(null);
            if (outcome instanceof CallOutcome.Ok<CatalogProduct> ok) {
                Optional<CatalogVariant> variant = variant(ok.value(), itemId);
                if (variant.isPresent()) {
                    return ToolOutcome.ok(result(active.get(i), ok.value(), variant.get()));
                }
            } else if (!(outcome instanceof CallOutcome.ProtocolError<CatalogProduct> e && e.code().equals("not_found"))) {
                unavailable = true;
            }
        }
        return ToolOutcome.error(unavailable ? "unavailable" : "not_found");
    }

    private static Optional<CatalogVariant> variant(CatalogProduct product, String itemId) {
        Optional<CatalogVariant> exact = product.variants().stream().filter(v -> v.itemId().equals(itemId)).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        CatalogVariant featured = product.variants().getFirst();
        return product.id().equals(itemId) && Args.ITEM_ID.matcher(featured.itemId()).matches()
                ? Optional.of(featured) : Optional.empty();
    }

    private static Map<String, Object> result(MerchantEntry merchant, CatalogProduct product, CatalogVariant variant) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("merchant_id", merchant.id().value());
        item.put("item_id", variant.itemId());
        item.put("title", ToolText.title(product, variant));
        item.put("description", ToolText.description(product.description().orElse("")));
        item.put("price_minor", variant.price().minor());
        item.put("currency", variant.price().currency());
        item.put("in_stock", variant.availability().available());
        if (!variant.options().isEmpty()) {
            item.put("options", ToolText.options(variant));
        }
        return item;
    }
}
