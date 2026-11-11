package dev.ucplab.merchant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The UCP Catalog capabilities over the in-memory {@link Catalog} (2026-08-25): {@code dev.ucp.shopping.catalog.search}
 * ({@code POST /catalog/search}) and {@code dev.ucp.shopping.catalog.lookup} ({@code POST /catalog/lookup} and
 * {@code POST /catalog/product}). Each lab item is one product with one variant. Both carry the item id, because the
 * variant id is the {@code item.id} a checkout takes. Prices are integer minor units. Availability is the schema's
 * {@code {available, status}}; raw stock counts are never exposed.
 *
 * <p>Business outcomes are HTTP 200 with a {@code messages} array: ids not found in a lookup are {@code info} messages,
 * and an unknown product is {@code ucp.status = error} with an unrecoverable {@code not_found}.
 */
final class CatalogApi {
    static final String SEARCH_CAPABILITY = "dev.ucp.shopping.catalog.search";
    static final String LOOKUP_CAPABILITY = "dev.ucp.shopping.catalog.lookup";
    static final String CURRENCY = "USD";
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final Catalog catalog;

    CatalogApi(Catalog catalog) {
        this.catalog = catalog;
    }

    // ------------------------------------------------------------------ search

    /** Items whose title contains any query word, best match first; an empty query matches everything. */
    Map<String, Object> search(Map<String, Object> req) {
        String query = MerchantCore.str(req.get("query"));
        String[] words = query == null ? new String[0] : query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        Map<String, Object> price = MerchantCore.asMap(MerchantCore.asMap(req.get("filters")).get("price"));
        long min = bound(price.get("min"), 0, "filters.price.min");
        long max = bound(price.get("max"), Long.MAX_VALUE, "filters.price.max");
        Map<String, Object> page = MerchantCore.asMap(req.get("pagination"));
        int limit = (int) Math.min(MAX_LIMIT, bound(page.get("limit"), DEFAULT_LIMIT, "pagination.limit"));
        if (limit < 1) throw new IllegalArgumentException("pagination.limit must be at least 1");
        int offset = cursor(page.get("cursor"));

        List<Catalog.Item> hits = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (Catalog.Item i : catalog.all()) {
            if (i.price() < min || i.price() > max) continue;
            int score = 0;
            String title = i.title().toLowerCase(Locale.ROOT);
            for (String w : words) if (!w.isEmpty() && title.contains(w)) score++;
            if (words.length > 0 && !words[0].isEmpty() && score == 0) continue;
            int at = 0;
            while (at < hits.size() && scores.get(at) >= score) at++;
            hits.add(at, i);
            scores.add(at, score);
        }
        int from = Math.min(offset, hits.size());
        int to = Math.min(hits.size(), from + limit);
        List<Object> products = new ArrayList<>();
        for (Catalog.Item i : hits.subList(from, to)) products.add(product(i));

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("has_next_page", to < hits.size());
        if (to < hits.size()) pagination.put("cursor", String.valueOf(to));
        pagination.put("total_count", hits.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ucp", envelope(SEARCH_CAPABILITY, false));
        out.put("products", products);
        out.put("pagination", pagination);
        return out;
    }

    // ------------------------------------------------------------------ lookup

    /** Products for each id (a product id or a variant id); unknown ids come back as {@code info not_found} messages. */
    Map<String, Object> lookup(Map<String, Object> req) {
        if (!(req.get("ids") instanceof List<?> ids) || ids.isEmpty()) {
            throw new IllegalArgumentException("ids must be a non-empty array");
        }
        List<Object> products = new ArrayList<>();
        List<Object> messages = new ArrayList<>();
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (Object o : ids) {
            if (!(o instanceof String id)) throw new IllegalArgumentException("ids must be strings");
            var item = catalog.find(id);
            if (item.isEmpty()) {
                messages.add(message("info", "not_found", id, null));
                continue;
            }
            Map<String, Object> product = seen.get(id);
            if (product == null) {
                product = product(item.get());
                @SuppressWarnings("unchecked")
                Map<String, Object> variant = ((List<Map<String, Object>>) product.get("variants")).get(0);
                variant.put("inputs", List.of(Map.of("id", id, "match", "exact")));
                seen.put(id, product);
                products.add(product);
            } else {
                @SuppressWarnings("unchecked")
                List<Object> inputs = (List<Object>) ((List<Map<String, Object>>) product.get("variants")).get(0).get("inputs");
                inputs.add(Map.of("id", id, "match", "exact"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ucp", envelope(LOOKUP_CAPABILITY, false));
        out.put("products", products);
        if (!messages.isEmpty()) out.put("messages", messages);
        return out;
    }

    // ------------------------------------------------------------------ product

    /** One product in full. An unknown id is HTTP 200 with {@code ucp.status = error} and an unrecoverable message. */
    Map<String, Object> product(Map<String, Object> req) {
        String id = MerchantCore.str(req.get("id"));
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        Map<String, Object> out = new LinkedHashMap<>();
        var item = catalog.find(id);
        if (item.isEmpty()) {
            out.put("ucp", envelope(LOOKUP_CAPABILITY, true));
            out.put("messages", List.of(message("error", "not_found", "Product not found: " + id, "unrecoverable")));
            return out;
        }
        out.put("ucp", envelope(LOOKUP_CAPABILITY, false));
        out.put("product", product(item.get()));
        return out;
    }

    // ------------------------------------------------------------------ shapes

    private static Map<String, Object> product(Catalog.Item i) {
        Map<String, Object> price = price(i.price());
        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("id", i.id());
        variant.put("title", i.title());
        variant.put("description", Map.of("plain", i.title()));
        variant.put("price", price);
        variant.put("availability", i.stock() > 0
                ? Map.of("available", true, "status", "in_stock")
                : Map.of("available", false, "status", "out_of_stock"));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", i.id());
        p.put("title", i.title());
        p.put("description", Map.of("plain", i.title()));
        p.put("price_range", Map.of("min", price, "max", price));
        p.put("variants", new ArrayList<>(List.of(variant)));
        return p;
    }

    private static Map<String, Object> price(long minor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount", minor);
        m.put("currency", CURRENCY);
        return m;
    }

    private static Map<String, Object> envelope(String capability, boolean error) {
        Map<String, Object> ucp = new LinkedHashMap<>();
        ucp.put("version", MerchantCore.VERSION);
        if (error) ucp.put("status", "error");
        ucp.put("capabilities", Map.of(capability, List.of(Map.of("version", MerchantCore.VERSION))));
        return ucp;
    }

    private static Map<String, Object> message(String type, String code, String content, String severity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("code", code);
        m.put("content", content);
        if (severity != null) m.put("severity", severity);
        return m;
    }

    private static long bound(Object v, long dflt, String name) {
        if (v == null) return dflt;
        if (v instanceof Long l && l >= 0) return l;
        throw new IllegalArgumentException(name + " must be a non-negative integer");
    }

    private static int cursor(Object v) {
        if (v == null) return 0;
        try {
            int n = Integer.parseInt(String.valueOf(v));
            if (n >= 0) return n;
        } catch (NumberFormatException ignored) {
            // falls through to the error below
        }
        throw new IllegalArgumentException("pagination.cursor is not valid");
    }
}
