package dev.ucplab.service.web;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.HandoffWatch;
import dev.ucplab.core.intent.IntentCursor;
import dev.ucplab.core.intent.IntentCursorCodec;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentPage;
import dev.ucplab.core.intent.IntentQueries;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.json.LenientJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's intents (23.2 [CONTRACT], m6-plan section 3.1). Every read is owner-scoped with the caller's id from
 * the session (T1, T2), so another user's intent is 404 exactly like a missing one (T6).
 *
 * <p>Bad paging input is 422 {@code validation_failed} with a fixed message that never echoes the input: a
 * {@code limit} outside 1 to 50 or not a number, a {@code state} that is not an {@link IntentState} name, and a cursor
 * that does not decode for this caller and state (Stage 2 review, point 2).
 */
@RestController
final class IntentController {

    static final String BAD_LIMIT = "limit must be a number from 1 to " + IntentQueries.MAX_LIMIT;
    static final String BAD_STATE = "state is not a known intent state";
    static final String BAD_CURSOR = "cursor is not valid";
    /** The failure_reason of a quote P9 refused (9.1). */
    private static final String OVER_BUDGET = "policy_P9";
    /** A link intent's completion, as its stored fingerprint names it (19, 26.7). */
    private static final String LINK = "link";

    private final IntentRepository intents;
    private final ApprovalRepository approvals;
    private final IntentQueries queries;
    private final IntentCursorCodec cursors;

    IntentController(IntentRepository intents, ApprovalRepository approvals, IntentQueries queries,
                     IntentCursorCodec cursors) {
        this.intents = Objects.requireNonNull(intents, "intents");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    @GetMapping("/api/intents")
    Map<String, Object> list(AuthContext auth, @RequestParam(name = "state", required = false) Optional<String> state,
                             @RequestParam(name = "cursor", required = false) Optional<String> cursor,
                             @RequestParam(name = "limit", required = false) Optional<String> limit) {
        Optional<IntentState> filter = state.map(IntentController::state);
        Optional<IntentCursor> after = cursor.map(c -> cursors.decode(auth.user(), filter, c)
                .orElseThrow(() -> ApiException.of(ErrorCode.VALIDATION_FAILED, BAD_CURSOR)));
        IntentPage page = queries.page(auth.user(), filter, after, limit.map(IntentController::limit)
                .orElse(IntentQueries.DEFAULT_LIMIT));
        List<Map<String, Object>> items = new ArrayList<>();
        for (PurchaseIntent i : page.intents()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", i.id().value());
            item.put("merchant_id", i.merchantId().value());
            item.put("state", i.state().name());
            item.put("created_at", i.createdAt().toString());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        page.next().ifPresent(n -> body.put("next_cursor", cursors.encode(auth.user(), filter, n)));
        return body;
    }

    @GetMapping("/api/intents/{id}")
    Map<String, Object> intent(AuthContext auth, @PathVariable("id") String id) {
        PurchaseIntent intent = owned(auth, id);
        Optional<ApprovalRecord> latest = approvals.findLatest(auth.user(), intent.id());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", intent.id().value());
        body.put("merchant_id", intent.merchantId().value());
        body.put("merchant", merchant(intent, latest));
        body.put("state", intent.state().name());
        intent.failureReason().ifPresent(r -> body.put("failure_reason", r));
        intent.orderId().ifPresent(o -> body.put("order_id", o));
        // 23.2, I9: only here, the owner's own read (owned() is owner-scoped). Present only in HANDED_OFF: both
        // repositories refuse it in any other state (V9's CHECK in SQL).
        intent.handoffUrl().ifPresent(u -> body.put("handoff_url", u.value().toString()));
        if (intent.state() == IntentState.HANDED_OFF) {
            putFollowUp(auth, intent, body);
        }
        latest.ifPresent(a -> {
            body.put("quote", QuoteCard.of(a.snapshot()));
            Map<String, Object> approval = new LinkedHashMap<>();
            approval.put("status", a.status().name());
            approval.put("expires_at", a.expiresAt().toString());
            body.put("approval", approval);
        });
        // 23.2, P9: the intent's own budget, and for a P9 refusal the total it refused, from the stored event (9.1).
        intent.budget().ifPresent(b -> body.put("budget", Map.of("amount_minor", b.amountMinor(), "currency", b.currency())));
        if (intent.failureReason().filter(OVER_BUDGET::equals).isPresent()) {
            refusedQuote(auth, intent).ifPresent(q -> body.put("refused_quote", q));
        }
        body.put("created_at", intent.createdAt().toString());
        return body;
    }

    /** The quoted total of the intent's latest P9 denial event, as numbers; empty when no such event names one. */
    private Optional<Map<String, Object>> refusedQuote(AuthContext auth, PurchaseIntent intent) {
        Optional<Map<String, Object>> quote = Optional.empty();
        for (IntentEvent e : queries.events(auth.user(), intent.id())) {
            if (!e.type().equals("policy_decision")) {
                continue;
            }
            Map<String, Object> payload = LenientJson.readObject(e.payloadJson());
            if ("P9".equals(payload.get("rule")) && payload.get("quoted") instanceof Map<?, ?> q
                    && q.get("currency") instanceof String currency && q.get("total_minor") instanceof Number total) {
                quote = Optional.of(Map.of("currency", currency, "total_minor", total.longValue()));
            }
        }
        return quote;
    }

    /**
     * 23.2, 19: the merchant as the values stored on the intent at creation describe it, never re-read from the
     * merchants file: its completion, its shop_domain only for a live store (LiveStore), and for a link intent with an
     * approval its seller, from the newest approval's snapshot (26.7).
     */
    private static Map<String, Object> merchant(PurchaseIntent intent, Optional<ApprovalRecord> latest) {
        Map<String, Object> stored = LenientJson.readObject(intent.merchantFingerprint());
        Map<String, Object> merchant = new LinkedHashMap<>();
        merchant.put("completion", stored.get("completion") instanceof String c ? c : "agent");
        Optional<String> shopDomain = stored.get("shop_domain") instanceof String d ? Optional.of(d) : Optional.empty();
        LiveStore.domain(Boolean.TRUE.equals(stored.get("owned")), shopDomain)
                .ifPresent(d -> merchant.put("shop_domain", d));
        if (LINK.equals(merchant.get("completion")) && latest.isPresent() && latest.get().snapshot().link().isPresent()) {
            merchant.put("seller", QuoteCard.seller(latest.get().snapshot().link().get()));
        }
        return merchant;
    }

    /**
     * 23.2, 7.6, I9: the handoff follow-up, only in HANDED_OFF and only on the owner's read. {@code store_order} is the
     * order the store reported, with its confirmation code; {@code follow_up} is whether it is still watching and, when
     * it ended without an order, why (from its handoff_follow_up_ended event). An intent handed off before V10 has
     * neither.
     */
    private void putFollowUp(AuthContext auth, PurchaseIntent intent, Map<String, Object> body) {
        // 26.7: a link intent is never followed up and never has a store order.
        if (LINK.equals(LenientJson.readObject(intent.merchantFingerprint()).get("completion"))) {
            body.put("follow_up", Map.of("watching", false));
            return;
        }
        HandoffWatch watch = intent.handoffWatch();
        watch.storeOrder().ifPresent(o -> {
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("id", o.id());
            o.label().ifPresent(l -> order.put("label", l));
            body.put("store_order", order);
        });
        Map<String, Object> followUp = new LinkedHashMap<>();
        if (watch.watching()) {
            followUp.put("watching", true);
        } else if (watch.storeOrder().isPresent()) {
            followUp.put("watching", false);
        } else {
            Optional<String> reason = endedReason(auth, intent);
            if (reason.isEmpty()) {
                return;
            }
            followUp.put("watching", false);
            followUp.put("ended_reason", reason.get());
        }
        body.put("follow_up", followUp);
    }

    private Optional<String> endedReason(AuthContext auth, PurchaseIntent intent) {
        Optional<String> reason = Optional.empty();
        for (IntentEvent e : queries.events(auth.user(), intent.id())) {
            if (e.type().equals("handoff_follow_up_ended")
                    && LenientJson.readObject(e.payloadJson()).get("reason") instanceof String r) {
                reason = Optional.of(r);
            }
        }
        return reason;
    }

    @GetMapping("/api/intents/{id}/events")
    List<Map<String, Object>> events(AuthContext auth, @PathVariable("id") String id) {
        PurchaseIntent intent = owned(auth, id);
        List<IntentEvent> events = queries.events(auth.user(), intent.id());
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            IntentEvent e = events.get(i);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("seq", i + 1);
            event.put("type", e.type());
            event.put("at", e.at().toString());
            event.put("payload", EventPayloads.filter(LenientJson.readObject(e.payloadJson())));
            out.add(event);
        }
        return out;
    }

    private PurchaseIntent owned(AuthContext auth, String id) {
        return intents.find(auth.user(), IntentIds.parse(id)).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND));
    }

    private static IntentState state(String name) {
        for (IntentState s : IntentState.values()) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        throw ApiException.of(ErrorCode.VALIDATION_FAILED, BAD_STATE);
    }

    private static int limit(String value) {
        try {
            return IntentPage.checkLimit(Integer.parseInt(value));
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, BAD_LIMIT);
        }
    }
}
