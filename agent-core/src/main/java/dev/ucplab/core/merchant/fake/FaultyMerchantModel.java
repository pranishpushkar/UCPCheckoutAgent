package dev.ucplab.core.merchant.fake;

import dev.ucplab.core.crypto.Sha256;
import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.Buyer;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.OrderRef;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * In-memory {@link MerchantGateway} with the lab merchant's semantics (Appendix A), plus per-call fault injection
 * (section 14, level 2). Its {@link #orders()} are the ground truth for I1 and I2, as {@code GET /_lab/orders} is for
 * the real merchant; tests assert on them, never on the orchestrator's report (13.1).
 *
 * <p>Merchant rules modelled from A.1 and A.2:
 * <ul>
 *   <li>Idempotency keys are scoped by method and path and stored without expiry. The same key and the same body
 *       returns the stored response; a different body returns 409. A duplicate of a request whose result is not yet
 *       known (the charge is still running) waits past the client timeout, so the client sees Uncertain.</li>
 *   <li>A session is {@code ready_for_complete} once it has line items, all available, and a buyer email.</li>
 *   <li>{@code complete} re-checks price and stock. A changed price returns {@code incomplete} with a
 *       {@code price_changed} warning; too little stock returns {@code incomplete} with {@code out_of_stock}. Neither
 *       charges. Stock is reserved before the charge and released on decline. A completed session is returned
 *       unchanged with no new order.</li>
 *   <li>PUT re-prices at the current catalog price. It is refused with {@code checkout_busy} during
 *       {@code complete_in_progress} and with {@code checkout_not_modifiable} once completed or canceled.</li>
 *   <li>A create whose every item is unavailable has no session id. The client reports it as
 *       {@code Rejected(200, messages)} (8.1, 7.2 {@code create_rejected}); the HTTP gateway maps it the same way.</li>
 * </ul>
 *
 * <p>Assumptions where Appendix A is silent (the merchant source is not in this repository): unknown items get
 * {@code item_unavailable}; create checks stock but does not reserve it; a token other than {@code tok_ok} or
 * {@code tok_decline} gets {@code payment_invalid}; a refused cancel gets {@code checkout_not_cancelable}.
 *
 * <p><b>Modes</b> (14.1). {@link Mode#LAB} is the lab merchant: a completed or charging session answers any further
 * {@code complete} without charging, so one session can never produce two orders. {@link Mode#STRICT} drops that
 * per-session protection, as a merchant that relies on idempotency keys alone might: a {@code complete} under a new key
 * re-checks and charges again, so each key can create its own order. Keys still replay their stored result, and a
 * canceled session is still refused.
 *
 * <p>Time is the injected clock. Held requests ({@link Fault.DelayForward}, {@link Fault.HoldInProgress}) are
 * delivered at the start of the next call or lab read once the clock has passed their due time. All state is guarded
 * by one {@link ReentrantLock} (7.8); nothing blocks while holding it.
 */
public final class FaultyMerchantModel implements MerchantGateway {

    public static final String UCP_VERSION = "2026-08-25";
    public static final String CURRENCY = "USD";
    public static final String HANDLER_ID = "mock_pay_1";
    public static final String TOKEN_OK = "tok_ok";
    public static final String TOKEN_DECLINE = "tok_decline";
    private static final int TAX_PERCENT = 8;

    /** How much the merchant protects a session against a second charge (14.1 "Merchant strictness"). */
    public enum Mode {
        /** The lab merchant (A.1): a completed or charging session is never charged again. */
        LAB,
        /** No per-session protection: each idempotency key can create its own order. */
        STRICT
    }

    private final MerchantId merchantId;
    private final Mode mode;
    private final Clock clock;
    private final IdGenerator ids;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Item> catalog = new LinkedHashMap<>();
    private final Map<SessionId, Session> sessions = new LinkedHashMap<>();
    private final Map<KeyScope, Stored> idempotency = new HashMap<>();
    private final List<ModelOrder> orders = new ArrayList<>();
    private final List<FaultRule> rules = new ArrayList<>();
    private final Map<MerchantCall, Integer> callCounts = new EnumMap<>(MerchantCall.class);
    private final Map<MerchantCall, Integer> deliveredCounts = new EnumMap<>(MerchantCall.class);
    private final List<Held> held = new ArrayList<>();
    private final List<CallRecord> log = new ArrayList<>();
    private int seq;
    private long heldSeq;

    public FaultyMerchantModel(MerchantId merchantId, Clock clock, IdGenerator ids) {
        this(merchantId, Mode.LAB, clock, ids);
    }

    public FaultyMerchantModel(MerchantId merchantId, Mode mode, Clock clock, IdGenerator ids) {
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        catalog.put("item_123", new Item(2500, 100));
        catalog.put("item_456", new Item(1200, 50));
        catalog.put("item_789", new Item(12000, 3));
    }

    // ---------------------------------------------------------------- lab controls (A.3)

    /** {@code POST /_lab/price}. */
    public void setPrice(String itemId, long priceMinor) {
        withLock(() -> requireItem(itemId).price = priceMinor);
    }

    /** {@code POST /_lab/stock}. */
    public void setStock(String itemId, int stock) {
        withLock(() -> requireItem(itemId).stock = stock);
    }

    public int stock(String itemId) {
        lock.lock();
        try {
            settleDue();
            return requireItem(itemId).stock;
        } finally {
            lock.unlock();
        }
    }

    public long price(String itemId) {
        lock.lock();
        try {
            return requireItem(itemId).price;
        } finally {
            lock.unlock();
        }
    }

    public void addRule(FaultRule rule) {
        Objects.requireNonNull(rule, "rule");
        withLock(() -> rules.add(rule));
    }

    public void clearRules() {
        withLock(rules::clear);
    }

    /** Ground truth, like {@code GET /_lab/orders}. Delivers due held requests first. */
    public List<ModelOrder> orders() {
        lock.lock();
        try {
            settleDue();
            return List.copyOf(orders);
        } finally {
            lock.unlock();
        }
    }

    /** The session as the merchant holds it now, without going through faults or the call log. */
    public Optional<CheckoutView> session(SessionId id) {
        lock.lock();
        try {
            settleDue();
            return Optional.ofNullable(sessions.get(id)).map(s -> view(s, List.of()));
        } finally {
            lock.unlock();
        }
    }

    public int sessionsCreated() {
        lock.lock();
        try {
            return sessions.size();
        } finally {
            lock.unlock();
        }
    }

    /** How many requests of this kind reached the merchant's handler (duplicates and late deliveries included). */
    public int delivered(MerchantCall call) {
        lock.lock();
        try {
            settleDue();
            return deliveredCounts.getOrDefault(call, 0);
        } finally {
            lock.unlock();
        }
    }

    /** Client calls and merchant deliveries in order, like {@code GET /_proxy/log}. */
    public List<CallRecord> log() {
        lock.lock();
        try {
            return List.copyOf(log);
        } finally {
            lock.unlock();
        }
    }

    /** Delivers held requests that are due. Every call and lab read does this first. */
    public void settle() {
        withLock(() -> { });
    }

    // ---------------------------------------------------------------- MerchantGateway

    @Override
    public MerchantProfile discover(MerchantId merchant) {
        return new MerchantProfile(merchant, UCP_VERSION, URI.create("http://127.0.0.1/ucp/v1"),
                Set.of("dev.ucp.shopping.checkout"), Set.of(HANDLER_ID));
    }

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(key, "key");
        return call(MerchantCall.CREATE, Optional.empty(), Optional.of(new KeyScope("POST", "/checkout-sessions", key)),
                CheckoutWire.createBody(request), (latency, sink) -> Optional.of(doCreate(request)));
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(key, "key");
        return call(MerchantCall.UPDATE, Optional.of(id),
                Optional.of(new KeyScope("PUT", "/checkout-sessions/" + id.value(), key)),
                CheckoutWire.updateBody(request), (latency, sink) -> Optional.of(doUpdate(id, request)));
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(key, "key");
        return call(MerchantCall.COMPLETE, Optional.of(id),
                Optional.of(new KeyScope("POST", "/checkout-sessions/" + id.value() + "/complete", key)),
                body.json(), (latency, sink) -> doComplete(id, body, latency, sink));
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        return call(MerchantCall.CANCEL, Optional.of(id),
                Optional.of(new KeyScope("POST", "/checkout-sessions/" + id.value() + "/cancel", key)),
                "", (latency, sink) -> Optional.of(doCancel(id)));
    }

    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        Objects.requireNonNull(id, "id");
        return call(MerchantCall.GET, Optional.of(id), Optional.empty(), "",
                (latency, sink) -> Optional.of(doGet(id)));
    }

    // ---------------------------------------------------------------- fault and delivery plumbing

    private CallOutcome<CheckoutView> call(
            MerchantCall call, Optional<SessionId> session, Optional<KeyScope> scope, String body, Handler handler) {
        lock.lock();
        try {
            settleDue();
            int n = callCounts.merge(call, 1, Integer::sum);
            List<Fault> faults = rules.stream().filter(r -> r.matches(call, n)).map(FaultRule::fault).toList();
            faults.stream().filter(f -> !f.isDelivery()).forEach(this::applyMerchantSide);
            Optional<Fault> delivery = faults.stream().filter(Fault::isDelivery).findFirst();
            record(call, session, scope, body, false, delivery.map(f -> f.getClass().getSimpleName()).orElse("none"));

            Runnable deliverOnce = () -> deliver(call, session, scope, body, handler, Optional.empty());
            if (delivery.isEmpty()) {
                return deliver(call, session, scope, body, handler, Optional.empty());
            }
            return switch (delivery.get()) {
                case Fault.NotSent f -> new CallOutcome.NotSent<>("connection refused (injected)");
                case Fault.Reject f ->
                        new CallOutcome.ProtocolError<>(f.httpStatus(), f.code(), "rejected (injected)", f.retryAfter());
                case Fault.CommitThenDrop f -> {
                    deliverOnce.run();
                    yield new CallOutcome.Uncertain<>("response dropped (injected)");
                }
                case Fault.DelayResponse f -> {
                    deliverOnce.run();
                    yield new CallOutcome.Uncertain<>("response arrived after the client timeout (injected)");
                }
                case Fault.DelayForward f -> {
                    held.add(new Held(clock.instant().plus(f.hold()), heldSeq++, deliverOnce));
                    yield new CallOutcome.Uncertain<>("request held at the proxy past the client timeout (injected)");
                }
                case Fault.HoldInProgress f ->
                        deliver(call, session, scope, body, handler, Optional.of(f.latency()));
                case Fault.Duplicate f -> {
                    CallOutcome<CheckoutView> first = deliver(call, session, scope, body, handler, Optional.empty());
                    deliverOnce.run();
                    yield first;
                }
                case Fault.PriceChange f -> throw new IllegalStateException("not a delivery fault: " + f);
                case Fault.StockChange f -> throw new IllegalStateException("not a delivery fault: " + f);
            };
        } finally {
            lock.unlock();
        }
    }

    /** The request reaches the merchant: idempotency first (A.1), then the handler. Caller holds the lock. */
    private CallOutcome<CheckoutView> deliver(
            MerchantCall call, Optional<SessionId> session, Optional<KeyScope> scope, String body, Handler handler,
            Optional<Duration> latency) {
        record(call, session, scope, body, true, "-");
        deliveredCounts.merge(call, 1, Integer::sum);
        if (scope.isEmpty()) {
            return handler.handle(latency, r -> { }).orElseThrow();
        }
        String bodyHash = Sha256.hex(body);
        Stored stored = idempotency.get(scope.get());
        if (stored != null) {
            if (!stored.bodyHash.equals(bodyHash)) {
                return new CallOutcome.ProtocolError<>(409, "idempotency_conflict",
                        "this key was used with a different body", Optional.empty());
            }
            return stored.result.orElseGet(
                    () -> new CallOutcome.Uncertain<>("duplicate waited for the first request past the client timeout"));
        }
        Stored fresh = new Stored(bodyHash);
        idempotency.put(scope.get(), fresh);
        Optional<CallOutcome<CheckoutView>> result = handler.handle(latency, r -> fresh.result = Optional.of(r));
        if (result.isEmpty()) {
            return new CallOutcome.Uncertain<>("charge still running at the client timeout");
        }
        fresh.result = result;
        return result.get();
    }

    private void applyMerchantSide(Fault fault) {
        switch (fault) {
            case Fault.PriceChange f -> requireItem(f.itemId()).price = f.newPriceMinor();
            case Fault.StockChange f -> requireItem(f.itemId()).stock = f.newStock();
            default -> throw new IllegalStateException("not a merchant-side fault: " + fault);
        }
    }

    /** Runs held deliveries whose time has come, earliest first. Caller holds the lock. */
    private void settleDue() {
        Instant now = clock.instant();
        while (true) {
            Optional<Held> next = held.stream()
                    .filter(h -> !h.dueAt.isAfter(now))
                    .min(Comparator.comparing(Held::dueAt).thenComparingLong(Held::order));
            if (next.isEmpty()) {
                return;
            }
            held.remove(next.get());
            next.get().action.run();
        }
    }

    private void record(
            MerchantCall call, Optional<SessionId> session, Optional<KeyScope> scope, String body, boolean delivered,
            String fault) {
        log.add(new CallRecord(++seq, call, session, scope.map(KeyScope::key), Sha256.hex(body), delivered, fault,
                clock.instant()));
    }

    // ---------------------------------------------------------------- merchant handlers (A.1)

    private CallOutcome<CheckoutView> doCreate(CreateCheckout request) {
        Priced priced = price(request.lines().stream()
                .map(l -> new Requested(l.itemId(), l.quantity(), Optional.empty())).toList());
        if (priced.lines.isEmpty()) {
            return new CallOutcome.Rejected<>(200, List.of(new MerchantMessage("error", "out_of_stock", Optional.empty(),
                    "All requested items are currently out of stock", Optional.of("unrecoverable"))));
        }
        Session s = new Session(new SessionId("cs_" + ids.next()), priced.lines,
                request.buyerEmail().map(Buyer::ofEmail).orElse(NO_BUYER));
        s.status = readiness(s);
        sessions.put(s.id, s);
        return ok(201, s, priced.messages);
    }

    private CallOutcome<CheckoutView> doUpdate(SessionId id, UpdateCheckout request) {
        Session s = sessions.get(id);
        if (s == null) {
            return notFound(id);
        }
        switch (s.status) {
            case COMPLETE_IN_PROGRESS -> {
                return ok(200, s, List.of(message("warning", "checkout_busy", "a charge is in progress")));
            }
            case COMPLETED, CANCELED -> {
                return ok(200, s, List.of(message("error", "checkout_not_modifiable", "checkout is " + s.status.wire())));
            }
            default -> {
                Priced priced = price(request.lines().stream()
                        .map(l -> new Requested(l.itemId(), l.quantity(), l.lineId())).toList());
                if (priced.lines.isEmpty()) {
                    // The merchant leaves the session as it was and says so.
                    List<MerchantMessage> messages = new ArrayList<>(priced.messages);
                    messages.add(new MerchantMessage("error", "out_of_stock", Optional.empty(),
                            "All requested items are currently out of stock", Optional.of("unrecoverable")));
                    return ok(200, s, messages);
                }
                s.lines = priced.lines;
                s.buyer = request.buyer().map(FaultyMerchantModel::keptBuyerFields).orElse(NO_BUYER);
                s.status = readiness(s);
                return ok(200, s, priced.messages);
            }
        }
    }

    private Optional<CallOutcome<CheckoutView>> doComplete(
            SessionId id, FrozenBody body, Optional<Duration> latency, Consumer<CallOutcome<CheckoutView>> sink) {
        Session s = sessions.get(id);
        if (s == null) {
            return Optional.of(notFound(id));
        }
        switch (s.status) {
            case COMPLETED -> {
                if (mode == Mode.LAB) {
                    return Optional.of(ok(200, s, List.of()));
                }
            }
            case CANCELED -> {
                return Optional.of(ok(200, s, List.of(message("error", "checkout_not_modifiable", "checkout is canceled"))));
            }
            case COMPLETE_IN_PROGRESS -> {
                if (mode == Mode.LAB) {
                    return Optional.of(ok(200, s, List.of(message("warning", "checkout_busy", "a charge is in progress"))));
                }
            }
            default -> {
                // Fall through to the re-checks below.
            }
        }
        // STRICT: a completed or charging session reaches the re-checks and is charged again.

        List<MerchantMessage> problems = new ArrayList<>();
        for (int i = 0; i < s.lines.size(); i++) {
            LineView line = s.lines.get(i);
            Item item = catalog.get(line.itemId());
            String path = "$.line_items[" + i + "]";
            if (item == null) {
                problems.add(message("error", "item_unavailable", path, line.itemId() + " is not sold"));
            } else if (item.price != line.unitPriceMinor()) {
                problems.add(message("warning", "price_changed", path,
                        line.itemId() + " now costs " + item.price + " (was " + line.unitPriceMinor() + ")"));
            } else if (item.stock < line.quantity()) {
                problems.add(message("error", "out_of_stock", path, line.itemId() + " has " + item.stock + " left"));
            }
        }
        if (problems.isEmpty() && s.buyer.email().isEmpty()) {
            problems.add(message("error", "checkout_incomplete", "$.buyer", "a buyer email is required"));
        }
        if (!problems.isEmpty()) {
            s.status = CheckoutStatus.INCOMPLETE;
            return Optional.of(ok(200, s, problems));
        }

        s.lines.forEach(l -> catalog.get(l.itemId()).stock -= l.quantity());
        Optional<String> token = CheckoutWire.paymentToken(body);
        if (latency.isEmpty()) {
            return Optional.of(charge(s, token));
        }
        s.status = CheckoutStatus.COMPLETE_IN_PROGRESS;
        held.add(new Held(clock.instant().plus(latency.get()), heldSeq++, () -> sink.accept(charge(s, token))));
        return Optional.empty();
    }

    /** The mock payment handler (A.2), after stock is reserved. */
    private CallOutcome<CheckoutView> charge(Session s, Optional<String> token) {
        if (token.filter(TOKEN_OK::equals).isPresent()) {
            String orderId = "ord_" + ids.next();
            orders.add(new ModelOrder(orderId, s.id, s.lines, CURRENCY, total(s), clock.instant()));
            s.status = CheckoutStatus.COMPLETED;
            s.order = Optional.of(new OrderRef(orderId));
            return ok(200, s, List.of());
        }
        s.lines.forEach(l -> catalog.get(l.itemId()).stock += l.quantity());
        s.status = CheckoutStatus.READY_FOR_COMPLETE;
        boolean declined = token.filter(TOKEN_DECLINE::equals).isPresent();
        return ok(200, s, List.of(declined
                ? message("error", "payment_declined", "$.payment", "the card was declined")
                : message("error", "payment_invalid", "$.payment", "the payment credential is not valid")));
    }

    private CallOutcome<CheckoutView> doCancel(SessionId id) {
        Session s = sessions.get(id);
        if (s == null) {
            return notFound(id);
        }
        switch (s.status) {
            case INCOMPLETE, READY_FOR_COMPLETE -> {
                s.status = CheckoutStatus.CANCELED;
                return ok(200, s, List.of());
            }
            case COMPLETE_IN_PROGRESS, COMPLETED -> {
                return ok(200, s, List.of(message("error", "checkout_not_cancelable", "checkout is " + s.status.wire())));
            }
            default -> {
                return ok(200, s, List.of());
            }
        }
    }

    private CallOutcome<CheckoutView> doGet(SessionId id) {
        Session s = sessions.get(id);
        return s == null ? notFound(id) : ok(200, s, List.of());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Mirrors {@code MerchantCore.resolveLines}: an unknown item or stock 0 drops the line with an
     * {@code item_unavailable} error; a quantity above the stock is cut to the stock with a
     * {@code quantity_adjusted} warning and the line stays.
     */
    private Priced price(List<Requested> requested) {
        List<LineView> lines = new ArrayList<>();
        List<MerchantMessage> messages = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            Requested line = requested.get(i);
            Item item = catalog.get(line.itemId());
            String path = "$.line_items[" + i + "]";
            if (item == null || item.stock == 0) {
                messages.add(new MerchantMessage("error", "item_unavailable", Optional.of(path),
                        "Item " + line.itemId() + " is not available", Optional.of("recoverable")));
                continue;
            }
            int quantity = line.quantity();
            if (quantity > item.stock) {
                messages.add(message("warning", "quantity_adjusted", path + ".quantity", "Quantity adjusted, requested "
                        + quantity + " units but only " + item.stock + " available"));
                quantity = item.stock;
            }
            // The merchant keeps a line id the request gave, and numbers the others by position.
            lines.add(new LineView(line.itemId(), quantity, item.price,
                    Optional.of(line.lineId().orElse("li_" + (lines.size() + 1)))));
        }
        return new Priced(List.copyOf(lines), List.copyOf(messages));
    }

    /** The merchant's state rule: ready once there is a buyer email, whatever the messages say. */
    private static CheckoutStatus readiness(Session s) {
        return s.buyer.email().isPresent() ? CheckoutStatus.READY_FOR_COMPLETE : CheckoutStatus.INCOMPLETE;
    }

    private CallOutcome<CheckoutView> ok(int httpStatus, Session s, List<MerchantMessage> messages) {
        return new CallOutcome.Ok<>(httpStatus, view(s, messages), messages);
    }

    private static CallOutcome<CheckoutView> notFound(SessionId id) {
        return new CallOutcome.ProtocolError<>(404, "not_found", "no checkout " + id.value(), Optional.empty());
    }

    private static CheckoutView view(Session s, List<MerchantMessage> messages) {
        long subtotal = subtotal(s);
        long tax = tax(subtotal);
        // The merchant reports a buyer only once it has an email.
        Optional<Buyer> buyer = s.buyer.email().isPresent() ? Optional.of(s.buyer) : Optional.empty();
        return new CheckoutView(s.id, s.status, CURRENCY, s.lines, subtotal, tax, subtotal + tax, s.order, buyer,
                messages);
    }

    private static long subtotal(Session s) {
        return s.lines.stream().mapToLong(l -> l.unitPriceMinor() * l.quantity()).sum();
    }

    /** Tax 8%, rounded half up on the subtotal (A.2). */
    static long tax(long subtotalMinor) {
        return (subtotalMinor * TAX_PERCENT + 50) / 100;
    }

    private static long total(Session s) {
        long subtotal = subtotal(s);
        return subtotal + tax(subtotal);
    }

    private static MerchantMessage message(String type, String code, String content) {
        return new MerchantMessage(type, code, Optional.empty(), content, Optional.empty());
    }

    private static MerchantMessage message(String type, String code, String path, String content) {
        return new MerchantMessage(type, code, Optional.of(path), content, Optional.empty());
    }

    private Item requireItem(String itemId) {
        Item item = catalog.get(Objects.requireNonNull(itemId, "itemId"));
        if (item == null) {
            throw new IllegalArgumentException("not in the catalog: " + itemId);
        }
        return item;
    }

    private void withLock(Runnable action) {
        lock.lock();
        try {
            settleDue();
            action.run();
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    private interface Handler {
        /** Empty when the result is deferred; it is then passed to {@code sink} when it is known. */
        Optional<CallOutcome<CheckoutView>> handle(Optional<Duration> latency, Consumer<CallOutcome<CheckoutView>> sink);
    }

    private record KeyScope(String method, String path, IdempotencyKey key) {}

    private record Held(Instant dueAt, long order, Runnable action) {}

    private record Priced(List<LineView> lines, List<MerchantMessage> messages) {}

    private record Requested(String itemId, int quantity, Optional<String> lineId) {}

    private static final Buyer NO_BUYER = new Buyer(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    /** The merchant reads email, first_name and last_name of a buyer and nothing else (applyBuyer). */
    private static Buyer keptBuyerFields(Buyer b) {
        return new Buyer(b.email(), b.firstName(), b.lastName(), Optional.empty());
    }

    private static final class Item {
        long price;
        int stock;

        Item(long price, int stock) {
            this.price = price;
            this.stock = stock;
        }
    }

    private static final class Stored {
        final String bodyHash;
        Optional<CallOutcome<CheckoutView>> result = Optional.empty();

        Stored(String bodyHash) {
            this.bodyHash = bodyHash;
        }
    }

    private static final class Session {
        final SessionId id;
        List<LineView> lines;
        Buyer buyer;
        CheckoutStatus status = CheckoutStatus.INCOMPLETE;
        Optional<OrderRef> order = Optional.empty();

        Session(SessionId id, List<LineView> lines, Buyer buyer) {
            this.id = id;
            this.lines = lines;
            this.buyer = buyer;
        }
    }
}
