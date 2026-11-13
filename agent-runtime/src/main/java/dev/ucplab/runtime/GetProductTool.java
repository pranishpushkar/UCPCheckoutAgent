package dev.ucplab.runtime;

import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
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
 * {@code get_product} (6.2): {@code item_id}. Asks each active merchant's catalog for the item and returns
 * {@code {merchant_id, item_id, title, description, price_minor, currency, in_stock}} for the variant with that id (the
 * featured variant when the id is a product's). {@code title} and {@code description} are wrapped; a product without a
 * description has an empty one. No merchant knows the id: {@code {"error":"not_found"}}; a merchant could not be
 * asked and none had it: {@code {"error":"unavailable"}}.
 */
final class GetProductTool implements Tool {

    static final String NAME = "get_product";
    private static final ToolSpec SPEC = new ToolSpec(NAME,
            "Returns one item with its description, price in minor units and stock.",
            CanonicalJson.write(Map.of(
                    "type", "object",
                    "properties", Map.of("item_id", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,100}$",
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
            itemId = Args.id(arguments, "item_id");
        } catch (Args.Invalid e) {
            return ToolOutcome.invalid(e.getMessage());
        }
        boolean unavailable = false;
        for (MerchantEntry merchant : ports.merchants().active()) {
            CatalogGateway catalog = ports.catalogs().get(merchant.id());
            CallOutcome<CatalogProduct> outcome = catalog == null ? null : catalog.product(itemId);
            if (outcome instanceof CallOutcome.Ok<CatalogProduct> ok) {
                Optional<CatalogVariant> variant = variant(ok.value(), itemId);
                if (variant.isPresent()) {
                    return ToolOutcome.ok(result(merchant, ok.value(), variant.get()));
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
        return product.id().equals(itemId) && Args.ID.matcher(featured.itemId()).matches()
                ? Optional.of(featured) : Optional.empty();
    }

    private static Map<String, Object> result(MerchantEntry merchant, CatalogProduct product, CatalogVariant variant) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("merchant_id", merchant.id().value());
        item.put("item_id", variant.itemId());
        item.put("title", ToolText.title(product, variant));
        item.put("description", UntrustedText.wrap("merchant", "description", product.description().orElse("")));
        item.put("price_minor", variant.price().minor());
        item.put("currency", variant.price().currency());
        item.put("in_stock", variant.availability().available());
        return item;
    }
}
