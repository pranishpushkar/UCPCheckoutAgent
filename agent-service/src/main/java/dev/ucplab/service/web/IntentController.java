package dev.ucplab.service.web;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.IntentCursor;
import dev.ucplab.core.intent.IntentCursorCodec;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
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
        body.put("state", intent.state().name());
        intent.failureReason().ifPresent(r -> body.put("failure_reason", r));
        intent.orderId().ifPresent(o -> body.put("order_id", o));
        latest.ifPresent(a -> {
            body.put("quote", QuoteCard.of(a.snapshot()));
            Map<String, Object> approval = new LinkedHashMap<>();
            approval.put("status", a.status().name());
            approval.put("expires_at", a.expiresAt().toString());
            body.put("approval", approval);
        });
        body.put("created_at", intent.createdAt().toString());
        return body;
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
        return intents.find(auth.user(), new IntentId(id)).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND));
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
