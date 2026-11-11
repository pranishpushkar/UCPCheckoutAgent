package dev.ucplab.merchant;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dependency-free tests. Run with scripts/test.sh. Starts the merchant on a random port and talks to it over HTTP,
 * so the routing, header rules and JSON handling are tested along with the business logic.
 */
public final class SelfTest {
    record Res(int status, Map<String, Object> body, String raw) {}

    static HttpClient http = HttpClient.newHttpClient();
    static String base;
    static int failures = 0, checks = 0;

    public static void main(String[] args) throws Exception {
        MerchantServer server = new MerchantServer(new MerchantCore(new Catalog(), new Payments(0)), 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        try {
            discovery();
            requiredHeaders();
            happyPath();
            idempotency();
            declinedPayment();
            priceChangeBetweenQuoteAndPayment();
            stockRules();
            raceOnSameSession();
            completeInProgressIsVisible();
            cancelRules();
            catalogCapabilities();
            catalogSearch();
            catalogLookupAndProduct();
        } finally {
            server.stop();
        }
        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ tests

    static void discovery() throws Exception {
        section("discovery");
        Res r = call("GET", "/.well-known/ucp", null, null);
        Map<String, Object> ucp = obj(r.body.get("ucp"));
        check(r.status == 200, "GET /.well-known/ucp returns 200");
        check(MerchantCore.VERSION.equals(ucp.get("version")), "profile advertises spec version " + MerchantCore.VERSION);
        check(obj(ucp.get("capabilities")).containsKey("dev.ucp.shopping.checkout"), "profile lists the checkout capability");
        List<Map<String, Object>> svc = MerchantCore.listOfMaps(obj(ucp.get("services")).get("dev.ucp.shopping"));
        check(!svc.isEmpty() && "rest".equals(svc.get(0).get("transport")), "profile lists the REST transport");
        check(String.valueOf(svc.get(0).get("endpoint")).equals(base + "/ucp/v1"), "endpoint is derived from the request host");
    }

    static void requiredHeaders() throws Exception {
        section("required headers");
        Res noAgent = raw("POST", "/ucp/v1/checkout-sessions", "{\"line_items\":[]}", Map.of("Request-Id", "r1", "Idempotency-Key", "k1"));
        check(noAgent.status == 400, "missing UCP-Agent is rejected with 400");
        Res noKey = raw("POST", "/ucp/v1/checkout-sessions", "{\"line_items\":[]}", Map.of("Request-Id", "r1", "UCP-Agent", "profile=\"https://p.example/.well-known/ucp\""));
        check(noKey.status == 400, "missing Idempotency-Key on POST is rejected with 400");
    }

    static void happyPath() throws Exception {
        section("happy path");
        reset();
        Res c = create(item("item_123", 2), "key-create-1");
        check(c.status == 201, "create returns 201");
        check("incomplete".equals(c.body.get("status")), "new session without buyer is incomplete");
        check(hasMessage(c, "missing"), "incomplete session explains what is missing");
        check(total(c, "subtotal") == 5000 && total(c, "tax") == 400 && total(c, "total") == 5400,
                "totals are 5000 + 400 tax = 5400");

        String id = (String) c.body.get("id");
        Res u = update(id, item("item_123", 2), "jane@example.com", "key-up-1");
        check(u.status == 200 && "ready_for_complete".equals(u.body.get("status")), "update with buyer email makes it ready_for_complete");

        Res done = complete(id, "tok_ok", "key-done-1");
        check("completed".equals(done.body.get("status")), "complete with tok_ok completes the checkout");
        check(obj(done.body.get("order")).get("id") != null, "completed checkout carries an order id");
        check(orderCount() == 1, "exactly one order exists");
        check(stock("item_123") == 98, "stock was reduced by the quantity bought");

        Res g = call("GET", "/ucp/v1/checkout-sessions/" + id, null, null);
        check("completed".equals(g.body.get("status")), "GET shows the completed checkout");
        check(call("GET", "/ucp/v1/checkout-sessions/nope", null, null).status == 404, "unknown session is 404");
    }

    static void idempotency() throws Exception {
        section("idempotency");
        reset();
        Res c1 = create(item("item_123", 1), "same-key");
        Res c2 = create(item("item_123", 1), "same-key");
        check(c1.status == 201 && c2.status == 201, "replayed create returns the stored 201");
        check(c1.raw.equals(c2.raw), "replayed create returns an identical body (same session id)");
        Res c3 = create(item("item_456", 1), "same-key");
        check(c3.status == 409, "same key with a different body is 409");

        String id = (String) c1.body.get("id");
        update(id, item("item_123", 1), "a@example.com", "u1");
        Res d1 = complete(id, "tok_ok", "dk1");
        Res d2 = complete(id, "tok_ok", "dk1");
        check(d1.raw.equals(d2.raw), "replayed complete returns the identical response");
        Res d3 = complete(id, "tok_ok", "dk2-different-key");
        check("completed".equals(d3.body.get("status")), "complete with a NEW key on a finished checkout just returns it");
        check(orderCount() == 1, "three complete calls produced one order");
    }

    static void declinedPayment() throws Exception {
        section("declined payment");
        reset();
        String id = readyCheckout(item("item_123", 1));
        Res d = complete(id, "tok_decline", "dk-decl");
        check("ready_for_complete".equals(d.body.get("status")), "declined payment leaves the checkout ready_for_complete");
        check(hasMessage(d, "payment_declined"), "response explains the decline");
        check(orderCount() == 0, "no order after a decline");
        check(stock("item_123") == 100, "stock was released after the decline");
        Res ok = complete(id, "tok_ok", "dk-retry");
        check("completed".equals(ok.body.get("status")) && orderCount() == 1, "retrying with a good token succeeds");
    }

    static void priceChangeBetweenQuoteAndPayment() throws Exception {
        section("price change between quote and payment");
        reset();
        String id = readyCheckout(item("item_123", 1));
        lab("/_lab/price", "{\"item_id\":\"item_123\",\"price\":3000}");
        Res d = complete(id, "tok_ok", "dk-price");
        check(!"completed".equals(d.body.get("status")), "merchant refuses to charge a changed price");
        check(hasMessage(d, "price_changed"), "response says the price changed");
        check(orderCount() == 0, "no order was created at the stale price");
        check(total(d, "subtotal") == 3000, "the new quote shows the new price");
        update(id, item("item_123", 1), "a@example.com", "u-reprice");
        Res ok = complete(id, "tok_ok", "dk-price-2");
        check("completed".equals(ok.body.get("status")), "after the buyer re-confirms, the checkout completes");
    }

    static void stockRules() throws Exception {
        section("stock");
        reset();
        Res c = create(item("item_789", 10), "k-stock-1");
        check(hasMessage(c, "quantity_adjusted"), "asking for more than stock adjusts the quantity with a warning");
        Res allOut = null;
        lab("/_lab/stock", "{\"item_id\":\"item_456\",\"stock\":0}");
        allOut = create(item("item_456", 1), "k-stock-2");
        check(allOut.status == 200 && allOut.body.get("id") == null && hasMessage(allOut, "out_of_stock"),
                "all items out of stock: 200 with an error message and no checkout");

        reset();
        String id = readyCheckout(item("item_789", 3));
        lab("/_lab/stock", "{\"item_id\":\"item_789\",\"stock\":1}");
        Res d = complete(id, "tok_ok", "dk-oos");
        check(hasMessage(d, "out_of_stock") && orderCount() == 0, "item sold out after add-to-cart: no order and a clear message");
    }

    static void raceOnSameSession() throws Exception {
        section("races");
        reset();
        lab("/_lab/config", "{\"payment_latency_ms\":60}");
        String id = readyCheckout(item("item_123", 1));
        ExecutorService pool = Executors.newFixedThreadPool(24);
        List<Future<Res>> futures = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            final String key = (i % 2 == 0) ? "shared-key" : "key-" + i;
            futures.add(pool.submit(() -> complete(id, "tok_ok", key)));
        }
        int completed = 0;
        for (Future<Res> f : futures) {
            if ("completed".equals(f.get().body.get("status"))) completed++;
        }
        pool.shutdown();
        check(orderCount() == 1, "24 concurrent completes (shared and different keys) made exactly one order");
        check(completed >= 1, "at least one caller saw the completed checkout");
        lab("/_lab/config", "{\"payment_latency_ms\":0}");
    }

    static void completeInProgressIsVisible() throws Exception {
        section("complete_in_progress");
        reset();
        lab("/_lab/config", "{\"payment_latency_ms\":400}");
        String id = readyCheckout(item("item_123", 1));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Res> done = pool.submit(() -> complete(id, "tok_ok", "dk-slow"));
        Thread.sleep(120);
        Res mid = call("GET", "/ucp/v1/checkout-sessions/" + id, null, null);
        check("complete_in_progress".equals(mid.body.get("status")), "GET during payment shows complete_in_progress");
        Res put = update(id, item("item_123", 5), "a@example.com", "u-during");
        check(hasMessage(put, "checkout_busy") && "complete_in_progress".equals(put.body.get("status")),
                "update during payment is refused and leaves the checkout unchanged");
        Res cancel = call("POST", "/ucp/v1/checkout-sessions/" + id + "/cancel", "{}", "c-during");
        check(!"canceled".equals(cancel.body.get("status")), "cancel during payment is refused");
        check("completed".equals(done.get().body.get("status")), "the in-flight complete still finishes");
        pool.shutdown();
        check(orderCount() == 1, "one order after the slow payment");
        lab("/_lab/config", "{\"payment_latency_ms\":0}");
    }

    static void cancelRules() throws Exception {
        section("cancel");
        reset();
        Res c = create(item("item_123", 1), "k-cancel");
        String id = (String) c.body.get("id");
        Res x = call("POST", "/ucp/v1/checkout-sessions/" + id + "/cancel", "{}", "kc1");
        check("canceled".equals(x.body.get("status")), "an open checkout can be canceled");
        Res after = update(id, item("item_123", 1), "a@example.com", "u-after-cancel");
        check(hasMessage(after, "checkout_not_modifiable"), "a canceled checkout cannot be updated");
    }

    // ------------------------------------------------------------------ helpers

    static String readyCheckout(String lineItemsJson) throws Exception {
        Res c = create(lineItemsJson, "rc-" + UUID.randomUUID());
        String id = (String) c.body.get("id");
        update(id, lineItemsJson, "a@example.com", "ru-" + UUID.randomUUID());
        return id;
    }

    static String item(String id, int qty) {
        return "[{\"item\":{\"id\":\"" + id + "\"},\"quantity\":" + qty + "}]";
    }

    static Res create(String lineItems, String key) throws Exception {
        return call("POST", "/ucp/v1/checkout-sessions", "{\"line_items\":" + lineItems + "}", key);
    }

    static Res update(String id, String lineItems, String email, String key) throws Exception {
        String body = "{\"buyer\":{\"email\":\"" + email + "\"},\"line_items\":" + lineItems + "}";
        return call("PUT", "/ucp/v1/checkout-sessions/" + id, body, key);
    }

    static Res complete(String id, String token, String key) throws Exception {
        String body = "{\"payment\":{\"instruments\":[{\"id\":\"pi_1\",\"handler_id\":\"" + MerchantCore.HANDLER_ID
                + "\",\"type\":\"mock_card\",\"selected\":true,\"credential\":{\"type\":\"MOCK\",\"token\":\"" + token + "\"}}]}}";
        return call("POST", "/ucp/v1/checkout-sessions/" + id + "/complete", body, key);
    }

    static Res call(String method, String path, String body, String idemKey) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Request-Id", UUID.randomUUID().toString());
        h.put("UCP-Agent", "profile=\"https://platform.example/.well-known/ucp\"");
        if (idemKey != null) h.put("Idempotency-Key", idemKey);
        return raw(method, path, body, h);
    }

    static Res raw(String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), Json.parseObject(r.body()), r.body());
    }

    static void lab(String path, String body) throws Exception {
        raw("POST", path, body, Map.of());
    }

    static void reset() throws Exception { lab("/_lab/reset", "{}"); }

    static int orderCount() throws Exception {
        return ((Number) raw("GET", "/_lab/orders", null, Map.of()).body.get("count")).intValue();
    }

    static int stock(String itemId) throws Exception {
        for (Map<String, Object> m : MerchantCore.listOfMaps(raw("GET", "/_lab/catalog", null, Map.of()).body.get("items"))) {
            if (itemId.equals(m.get("id"))) return ((Number) m.get("stock")).intValue();
        }
        return -1;
    }

    // ------------------------------------------------------------------ catalog (added after the original checks)

    static Res catalogCall(String path, String body) throws Exception {
        return call("POST", "/ucp/v1/catalog/" + path, body, null);
    }

    static List<Map<String, Object>> products(Res r) { return MerchantCore.listOfMaps(r.body.get("products")); }

    static Map<String, Object> variant(Map<String, Object> product) {
        return MerchantCore.listOfMaps(product.get("variants")).get(0);
    }

    static void catalogCapabilities() throws Exception {
        section("catalog: discovery and headers");
        Map<String, Object> caps = obj(obj(call("GET", "/.well-known/ucp", null, null).body.get("ucp")).get("capabilities"));
        check(caps.containsKey("dev.ucp.shopping.catalog.search"), "profile lists dev.ucp.shopping.catalog.search");
        check(caps.containsKey("dev.ucp.shopping.catalog.lookup"), "profile lists dev.ucp.shopping.catalog.lookup");
        check(caps.containsKey("dev.ucp.shopping.checkout"), "profile still lists the checkout capability");
        Res noAgent = raw("POST", "/ucp/v1/catalog/search", "{}", Map.of("Request-Id", "r1"));
        check(noAgent.status == 400, "catalog call without UCP-Agent is rejected with 400");
        Res ok = catalogCall("search", "{}");
        check(ok.status == 200, "catalog call needs no Idempotency-Key");
        check(raw("POST", "/ucp/v1/catalog/search", "[]",
                Map.of("Request-Id", "r1", "UCP-Agent", "profile=\"https://p.example/.well-known/ucp\"")).status == 400,
                "a body that is not a JSON object is rejected with 400");
        check(call("GET", "/ucp/v1/catalog/search", null, null).status == 404, "catalog search is POST only");
    }

    static void catalogSearch() throws Exception {
        section("catalog: search");
        reset();
        Res all = catalogCall("search", "{}");
        check(all.status == 200 && products(all).size() == 3, "an empty search returns all three products");
        check(obj(all.body.get("ucp")).get("version").equals(MerchantCore.VERSION), "response carries the ucp version");
        Res mug = catalogCall("search", "{\"query\":\"blue mug\"}");
        check(products(mug).size() == 1 && "item_456".equals(products(mug).get(0).get("id")), "search by title finds the Blue Mug");
        Map<String, Object> p = products(mug).get(0);
        check(((Number) obj(obj(p.get("price_range")).get("min")).get("amount")).longValue() == 1200
                && "USD".equals(obj(obj(p.get("price_range")).get("min")).get("currency")), "price is integer minor units with a currency");
        check("item_456".equals(variant(p).get("id")) && ((Number) obj(variant(p).get("price")).get("amount")).longValue() == 1200,
                "the variant id is the item id a checkout takes");
        check(products(catalogCall("search", "{\"query\":\"zzz\"}")).isEmpty(), "a query that matches nothing returns no products");
        Res cheap = catalogCall("search", "{\"filters\":{\"price\":{\"max\":2500}}}");
        check(products(cheap).size() == 2, "filters.price.max narrows the results");
        Res page = catalogCall("search", "{\"pagination\":{\"limit\":2}}");
        Map<String, Object> pg = obj(page.body.get("pagination"));
        check(products(page).size() == 2 && Boolean.TRUE.equals(pg.get("has_next_page")) && pg.get("cursor") != null,
                "pagination.limit returns a page with a cursor");
        Res next = catalogCall("search", "{\"pagination\":{\"limit\":2,\"cursor\":\"" + pg.get("cursor") + "\"}}");
        check(products(next).size() == 1 && Boolean.FALSE.equals(obj(next.body.get("pagination")).get("has_next_page")),
                "the cursor returns the last page");
        check(catalogCall("search", "{\"pagination\":{\"limit\":0}}").status == 400, "a limit below 1 is rejected with 400");
    }

    static void catalogLookupAndProduct() throws Exception {
        section("catalog: lookup, product, availability");
        reset();
        Res l = catalogCall("lookup", "{\"ids\":[\"item_123\",\"nope\"]}");
        check(l.status == 200 && products(l).size() == 1, "lookup returns the found products and skips unknown ids");
        check(MerchantCore.listOfMaps(l.body.get("messages")).stream().anyMatch(m -> "not_found".equals(m.get("code"))
                && "info".equals(m.get("type")) && "nope".equals(m.get("content"))), "an unknown id is an info not_found message");
        check(!MerchantCore.listOfMaps(variant(products(l).get(0)).get("inputs")).isEmpty(), "a looked-up variant says which input resolved to it");
        check(catalogCall("lookup", "{\"ids\":[]}").status == 400, "lookup with no ids is rejected with 400");
        Res d = catalogCall("product", "{\"id\":\"item_789\"}");
        Map<String, Object> product = obj(d.body.get("product"));
        check(d.status == 200 && "item_789".equals(product.get("id")), "product returns one product object");
        check(Boolean.TRUE.equals(obj(variant(product).get("availability")).get("available"))
                && "in_stock".equals(obj(variant(product).get("availability")).get("status")), "an item with stock is available and in_stock");
        check(!d.raw.contains("stock\":"), "no raw stock count is exposed");
        lab("/_lab/stock", "{\"item_id\":\"item_789\",\"stock\":0}");
        Res gone = catalogCall("product", "{\"id\":\"item_789\"}");
        check(Boolean.FALSE.equals(obj(variant(obj(gone.body.get("product"))).get("availability")).get("available"))
                && "out_of_stock".equals(obj(variant(obj(gone.body.get("product"))).get("availability")).get("status")),
                "stock 0 is available false, out_of_stock");
        lab("/_lab/price", "{\"item_id\":\"item_789\",\"price\":9900}");
        check(((Number) obj(variant(obj(catalogCall("product", "{\"id\":\"item_789\"}").body.get("product"))).get("price")).get("amount")).longValue() == 9900,
                "the catalog shows the lab's current price");
        Res missing = catalogCall("product", "{\"id\":\"nope\"}");
        check(missing.status == 200 && "error".equals(obj(missing.body.get("ucp")).get("status")), "an unknown product is 200 with ucp.status error");
        check(MerchantCore.listOfMaps(missing.body.get("messages")).stream().anyMatch(m -> "not_found".equals(m.get("code"))
                && "unrecoverable".equals(m.get("severity"))), "the unknown product message is unrecoverable not_found");
        check(catalogCall("product", "{}").status == 400, "product without an id is rejected with 400");
        reset();
    }

    static boolean hasMessage(Res r, String code) {
        for (Map<String, Object> m : MerchantCore.listOfMaps(r.body.get("messages"))) {
            if (code.equals(m.get("code"))) return true;
        }
        return false;
    }

    static long total(Res r, String type) {
        for (Map<String, Object> m : MerchantCore.listOfMaps(r.body.get("totals"))) {
            if (type.equals(m.get("type"))) return ((Number) m.get("amount")).longValue();
        }
        return -1;
    }

    static Map<String, Object> obj(Object o) { return MerchantCore.asMap(o); }

    static void section(String name) { System.out.println("\n" + name); }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
    }
}
