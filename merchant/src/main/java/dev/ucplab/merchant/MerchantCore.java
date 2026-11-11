package dev.ucplab.merchant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The merchant's business logic for the UCP Checkout capability (REST binding, spec version 2026-08-25).
 * No HTTP in here: the adapter in MerchantServer turns requests into calls on this class.
 *
 * Checkout status flow:
 *   incomplete -> ready_for_complete -> complete_in_progress -> completed
 *   (any non-final state) -> canceled
 *
 * Business outcomes (out of stock, declined, price changed) are normal 200 responses with a messages array,
 * as the spec requires. Protocol problems (bad headers, idempotency conflicts) are HTTP errors in the adapter.
 */
final class MerchantCore {
    static final String VERSION = "2026-08-25";
    static final String HANDLER_ID = "mock_pay_1";
    static final long TAX_BPS = 800; // 8.00%

    record Response(int status, Map<String, Object> body) {}
    record Line(String id, String itemId, String title, long price, int quantity) {}
    record Order(String id, String checkoutId, String buyerEmail, long amount, String token) {}

    static final class Session {
        final String id;
        volatile String status = "incomplete";
        List<Line> lines = List.of();
        String email, firstName, lastName;
        long quotedTotal;
        String orderId;

        Session(String id) { this.id = id; }
    }

    private static final class IdemEntry {
        final String fingerprint;
        final ReentrantLock lock = new ReentrantLock();
        Response result;

        IdemEntry(String fingerprint) { this.fingerprint = fingerprint; }
    }

    private final Catalog catalog;
    private final Payments payments;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final List<Order> orders = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, IdemEntry> idem = new ConcurrentHashMap<>();
    private final AtomicInteger sessionSeq = new AtomicInteger();
    private final AtomicInteger orderSeq = new AtomicInteger();

    MerchantCore(Catalog catalog, Payments payments) {
        this.catalog = catalog;
        this.payments = payments;
    }

    // ------------------------------------------------------------------ idempotency

    /**
     * Run op once per (scope, key). A repeat with the same body gets the stored response.
     * A repeat with a different body is a 409. Concurrent duplicates wait for the first call and share its result.
     */
    Response idempotent(String scope, String key, Object body, Supplier<Response> op) {
        String fp = fingerprint(body);
        IdemEntry e = idem.computeIfAbsent(scope + "|" + key, k -> new IdemEntry(fp));
        // ReentrantLock, not synchronized: the operation may block (a slow payment), and blocking inside
        // synchronized pins a virtual thread's carrier on Java 21, which starves other requests.
        e.lock.lock();
        try {
            if (!e.fingerprint.equals(fp)) {
                return new Response(409, protocolError("idempotency_key_reuse",
                        "Idempotency-Key was reused with a different request body"));
            }
            if (e.result == null) e.result = op.get();
            return e.result;
        } finally {
            e.lock.unlock();
        }
    }

    private static String fingerprint(Object body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(Json.canonical(body == null ? Map.of() : body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------------ checkout operations

    Response create(Map<String, Object> req) {
        List<Map<String, Object>> in = listOfMaps(req.get("line_items"));
        if (in.isEmpty()) return new Response(400, protocolError("invalid_request", "line_items is required"));

        List<Map<String, Object>> msgs = new ArrayList<>();
        List<Line> lines = resolveLines(in, msgs);
        if (lines.isEmpty()) {
            return new Response(200, errorEnvelope("out_of_stock", "All requested items are currently out of stock"));
        }
        Session s = new Session("chk_" + (1000 + sessionSeq.incrementAndGet()));
        s.lines = lines;
        applyBuyer(s, req.get("buyer"));
        s.quotedTotal = total(lines);
        refreshStatus(s);
        sessions.put(s.id, s);
        return new Response(201, render(s, msgs));
    }

    Response get(String id) {
        Session s = sessions.get(id);
        if (s == null) return notFound(id);
        synchronized (s) {
            return new Response(200, render(s, List.of()));
        }
    }

    /** PUT is a full replacement of the buyer and line items, as the spec requires. */
    Response update(String id, Map<String, Object> req) {
        Session s = sessions.get(id);
        if (s == null) return notFound(id);
        synchronized (s) {
            List<Map<String, Object>> msgs = new ArrayList<>();
            switch (s.status) {
                case "complete_in_progress" -> {
                    msgs.add(error("checkout_busy", "Checkout is being completed, so the update was not applied", "recoverable", null));
                    return new Response(200, render(s, msgs));
                }
                case "completed", "canceled" -> {
                    msgs.add(error("checkout_not_modifiable", "Checkout is " + s.status + " and cannot be changed", "unrecoverable", null));
                    return new Response(200, render(s, msgs));
                }
                default -> { }
            }
            List<Map<String, Object>> in = listOfMaps(req.get("line_items"));
            if (in.isEmpty()) return new Response(400, protocolError("invalid_request", "line_items is required"));

            List<Line> lines = resolveLines(in, msgs);
            if (lines.isEmpty()) {
                msgs.add(error("out_of_stock", "All requested items are currently out of stock", "unrecoverable", null));
                return new Response(200, render(s, msgs));
            }
            s.lines = lines;
            applyBuyer(s, req.get("buyer"));
            s.quotedTotal = total(lines);
            refreshStatus(s);
            return new Response(200, render(s, msgs));
        }
    }

    /**
     * Place the order. Stock is reserved before the charge and returned if the charge fails.
     * Only one caller can move a session from ready_for_complete to complete_in_progress,
     * so a session can produce at most one order however many times complete is called.
     */
    Response complete(String id, Map<String, Object> req) {
        Session s = sessions.get(id);
        if (s == null) return notFound(id);

        String token;
        long amount;
        String email;
        Map<String, Integer> need;

        synchronized (s) {
            List<Map<String, Object>> msgs = new ArrayList<>();
            switch (s.status) {
                case "completed" -> { return new Response(200, render(s, msgs)); }
                case "canceled" -> {
                    msgs.add(error("checkout_canceled", "Checkout was canceled", "unrecoverable", null));
                    return new Response(200, render(s, msgs));
                }
                case "complete_in_progress" -> {
                    msgs.add(error("complete_in_progress", "Checkout is already being completed", "recoverable", null));
                    return new Response(200, render(s, msgs));
                }
                case "incomplete" -> {
                    msgs.add(error("not_ready", "Checkout is missing required information", "recoverable", null));
                    return new Response(200, render(s, msgs));
                }
                default -> { }
            }

            Map<String, Object> instrument = firstInstrument(req);
            if (instrument == null || !HANDLER_ID.equals(instrument.get("handler_id"))) {
                msgs.add(error("missing", "A payment instrument for handler " + HANDLER_ID + " is required",
                        "recoverable", "$.payment.instruments"));
                return new Response(200, render(s, msgs));
            }
            token = str(asMap(instrument.get("credential")).get("token"));

            // Re-check stock and price at the moment of purchase.
            boolean priceChanged = false;
            List<Line> fresh = new ArrayList<>();
            for (int i = 0; i < s.lines.size(); i++) {
                Line l = s.lines.get(i);
                Optional<Catalog.Item> item = catalog.find(l.itemId());
                if (item.isEmpty() || item.get().stock() < l.quantity()) {
                    msgs.add(error("out_of_stock", "Item " + l.itemId() + " is no longer available in the requested quantity",
                            "recoverable", "$.line_items[" + i + "]"));
                    return new Response(200, render(s, msgs));
                }
                long price = item.get().price();
                if (price != l.price()) priceChanged = true;
                fresh.add(new Line(l.id(), l.itemId(), l.title(), price, l.quantity()));
            }
            if (priceChanged) {
                s.lines = fresh;
                s.quotedTotal = total(fresh);
                s.status = "incomplete";
                msgs.add(warning("price_changed", "Prices changed since the last quote; review the new total and update the checkout", "$.totals"));
                return new Response(200, render(s, msgs));
            }

            need = new LinkedHashMap<>();
            for (Line l : s.lines) need.merge(l.itemId(), l.quantity(), Integer::sum);
            if (!catalog.takeAll(need)) {
                msgs.add(error("out_of_stock", "An item sold out while completing the checkout", "recoverable", null));
                return new Response(200, render(s, msgs));
            }
            amount = s.quotedTotal;
            email = s.email;
            s.status = "complete_in_progress";
        }

        // The charge runs outside the lock so GET and PUT can observe complete_in_progress.
        Payments.Result r = payments.charge(token, amount);

        synchronized (s) {
            List<Map<String, Object>> msgs = new ArrayList<>();
            if (!r.approved()) {
                catalog.giveBack(need);
                s.status = "ready_for_complete";
                msgs.add(error("payment_declined", "Payment was declined: " + r.code(), "recoverable", "$.payment"));
                return new Response(200, render(s, msgs));
            }
            Order o = new Order("ord_" + (5000 + orderSeq.incrementAndGet()), s.id, email, amount, token);
            orders.add(o);
            s.orderId = o.id();
            s.status = "completed";
            return new Response(200, render(s, msgs));
        }
    }

    Response cancel(String id) {
        Session s = sessions.get(id);
        if (s == null) return notFound(id);
        synchronized (s) {
            List<Map<String, Object>> msgs = new ArrayList<>();
            switch (s.status) {
                case "completed" -> msgs.add(error("already_completed", "Checkout is completed and cannot be canceled", "unrecoverable", null));
                case "complete_in_progress" -> msgs.add(error("complete_in_progress", "Checkout is being completed and cannot be canceled yet", "recoverable", null));
                default -> s.status = "canceled";
            }
            return new Response(200, render(s, msgs));
        }
    }

    // ------------------------------------------------------------------ discovery

    Map<String, Object> discovery(String baseUrl) {
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("version", VERSION);
        service.put("spec", "https://ucp.dev/" + VERSION + "/specification/overview");
        service.put("transport", "rest");
        service.put("schema", "https://ucp.dev/" + VERSION + "/services/shopping/rest.openapi.json");
        service.put("endpoint", baseUrl + "/ucp/v1");

        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("version", VERSION);
        cap.put("spec", "https://ucp.dev/" + VERSION + "/specification/shopping/checkout");
        cap.put("schema", "https://ucp.dev/" + VERSION + "/schemas/shopping/checkout.json");

        Map<String, Object> ucp = new LinkedHashMap<>();
        ucp.put("version", VERSION);
        ucp.put("services", Map.of("dev.ucp.shopping", List.of(service)));
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("dev.ucp.shopping.checkout", List.of(cap));
        capabilities.put(CatalogApi.SEARCH_CAPABILITY, List.of(catalogCap("search")));
        capabilities.put(CatalogApi.LOOKUP_CAPABILITY, List.of(catalogCap("lookup")));
        ucp.put("capabilities", capabilities);
        ucp.put("payment_handlers", handlers());
        return Map.of("ucp", ucp);
    }

    private static Map<String, Object> catalogCap(String name) {
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("version", VERSION);
        cap.put("spec", "https://ucp.dev/" + VERSION + "/specification/shopping/catalog/" + name);
        cap.put("schema", "https://ucp.dev/" + VERSION + "/schemas/shopping/catalog_" + name + ".json");
        return cap;
    }

    // ------------------------------------------------------------------ lab-only helpers (not part of UCP)

    List<Map<String, Object>> ordersJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (orders) {
            for (Order o : orders) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", o.id());
                m.put("checkout_id", o.checkoutId());
                m.put("buyer_email", o.buyerEmail());
                m.put("amount", o.amount());
                out.add(m);
            }
        }
        return out;
    }

    List<Map<String, Object>> catalogJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Catalog.Item i : catalog.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.id());
            m.put("title", i.title());
            m.put("price", i.price());
            m.put("stock", i.stock());
            out.add(m);
        }
        return out;
    }

    void reset() {
        sessions.clear();
        orders.clear();
        idem.clear();
        catalog.reset();
        payments.setLatencyMs(0);
    }

    Catalog catalog() { return catalog; }

    Payments payments() { return payments; }

    // ------------------------------------------------------------------ rendering

    private Map<String, Object> render(Session s, List<Map<String, Object>> extra) {
        Map<String, Object> b = new LinkedHashMap<>();
        Map<String, Object> ucp = new LinkedHashMap<>();
        ucp.put("version", VERSION);
        ucp.put("capabilities", Map.of("dev.ucp.shopping.checkout", List.of(Map.of("version", VERSION))));
        ucp.put("payment_handlers", handlers());
        b.put("ucp", ucp);
        b.put("id", s.id);
        b.put("status", s.status);
        b.put("currency", "USD");

        List<Object> items = new ArrayList<>();
        long subtotal = 0;
        for (Line l : s.lines) {
            long lineTotal = l.price() * l.quantity();
            subtotal += lineTotal;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", l.itemId());
            item.put("title", l.title());
            item.put("price", l.price());
            Map<String, Object> li = new LinkedHashMap<>();
            li.put("id", l.id());
            li.put("item", item);
            li.put("quantity", l.quantity());
            li.put("totals", List.of(total("subtotal", lineTotal), total("total", lineTotal)));
            items.add(li);
        }
        b.put("line_items", items);

        if (s.email != null) {
            Map<String, Object> buyer = new LinkedHashMap<>();
            buyer.put("email", s.email);
            if (s.firstName != null) buyer.put("first_name", s.firstName);
            if (s.lastName != null) buyer.put("last_name", s.lastName);
            b.put("buyer", buyer);
        }

        long tax = tax(subtotal);
        b.put("totals", List.of(total("subtotal", subtotal), total("tax", tax), total("total", subtotal + tax)));
        b.put("links", List.of(Map.of("type", "terms_of_service", "url", "https://merchant.example/terms")));

        List<Map<String, Object>> msgs = new ArrayList<>();
        if (s.status.equals("incomplete") && s.email == null) {
            msgs.add(error("missing", "Buyer email is required", "recoverable", "$.buyer.email"));
        }
        msgs.addAll(extra);
        if (!msgs.isEmpty()) b.put("messages", msgs);

        if (s.orderId != null) {
            b.put("order", Map.of("id", s.orderId, "permalink_url", "https://merchant.example/orders/" + s.orderId));
        }
        b.put("continue_url", "https://merchant.example/checkout/" + s.id);
        return b;
    }

    private static Map<String, Object> handlers() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", HANDLER_ID);
        h.put("version", VERSION);
        h.put("available_instruments", List.of(Map.of("type", "mock_card")));
        h.put("config", Map.of());
        return Map.of("dev.ucplab.mock_pay", List.of(h));
    }

    private static Map<String, Object> total(String type, long amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("amount", amount);
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private List<Line> resolveLines(List<Map<String, Object>> in, List<Map<String, Object>> msgs) {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            Map<String, Object> m = in.get(i);
            String itemId = str(asMap(m.get("item")).get("id"));
            int qty = m.get("quantity") instanceof Number n ? n.intValue() : 1;
            String path = "$.line_items[" + i + "]";
            Optional<Catalog.Item> item = itemId == null ? Optional.empty() : catalog.find(itemId);
            if (qty < 1 || item.isEmpty() || item.get().stock() == 0) {
                msgs.add(error("item_unavailable", "Item " + itemId + " is not available", "recoverable", path));
                continue;
            }
            if (qty > item.get().stock()) {
                msgs.add(warning("quantity_adjusted", "Quantity adjusted, requested " + qty + " units but only "
                        + item.get().stock() + " available", path + ".quantity"));
                qty = item.get().stock();
            }
            String lineId = m.get("id") instanceof String sid ? sid : "li_" + (lines.size() + 1);
            lines.add(new Line(lineId, itemId, item.get().title(), item.get().price(), qty));
        }
        return lines;
    }

    private void applyBuyer(Session s, Object buyer) {
        Map<String, Object> b = asMap(buyer);
        s.email = str(b.get("email"));
        s.firstName = str(b.get("first_name"));
        s.lastName = str(b.get("last_name"));
    }

    private void refreshStatus(Session s) {
        if (s.status.equals("incomplete") || s.status.equals("ready_for_complete")) {
            s.status = s.email != null ? "ready_for_complete" : "incomplete";
        }
    }

    private static long total(List<Line> lines) {
        long subtotal = 0;
        for (Line l : lines) subtotal += l.price() * l.quantity();
        return subtotal + tax(subtotal);
    }

    private static long tax(long subtotal) { return (subtotal * TAX_BPS + 5000) / 10000; }

    private static Map<String, Object> firstInstrument(Map<String, Object> req) {
        List<Map<String, Object>> list = listOfMaps(asMap(req.get("payment")).get("instruments"));
        return list.isEmpty() ? null : list.get(0);
    }

    private Response notFound(String id) {
        return new Response(404, protocolError("not_found", "No checkout session " + id));
    }

    static Map<String, Object> protocolError(String code, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("content", content);
        return m;
    }

    private Map<String, Object> errorEnvelope(String code, String content) {
        Map<String, Object> ucp = new LinkedHashMap<>();
        ucp.put("version", VERSION);
        ucp.put("status", "error");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("ucp", ucp);
        b.put("messages", List.of(error(code, content, "unrecoverable", null)));
        b.put("continue_url", "https://merchant.example/");
        return b;
    }

    private static Map<String, Object> error(String code, String content, String severity, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "error");
        m.put("code", code);
        if (path != null) m.put("path", path);
        m.put("content", content);
        m.put("severity", severity);
        return m;
    }

    private static Map<String, Object> warning(String code, String content, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "warning");
        m.put("code", code);
        if (path != null) m.put("path", path);
        m.put("content", content);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOfMaps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object e : l) if (e instanceof Map) out.add((Map<String, Object>) e);
        }
        return out;
    }

    static String str(Object o) { return o instanceof String s ? s : null; }
}
