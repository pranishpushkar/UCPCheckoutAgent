package dev.ucplab.eval.check;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.SnapshotLine;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.AttemptId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.eval.AutoApprover;
import dev.ucplab.eval.GroundTruth;
import dev.ucplab.eval.RunEvidence;
import dev.ucplab.eval.Scenarios;
import dev.ucplab.eval.TimeScale;
import dev.ucplab.faultproxy.LogEntry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Hand-made evidence of one clean S0 run at time scale 4, built field by field so a test can break exactly one thing:
 * create, quote, approve at +200 ms, complete at +260 ms, COMPLETED at +400 ms, one order.
 */
final class CheckFixture {

    static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");
    static final byte[] KEY = key();
    static final CheckContext CONTEXT = CheckContext.of(TimeScale.of(4), KEY, Scenarios.SHOP, Duration.ofMillis(20));

    static final IntentId INTENT = new IntentId("it-1");
    static final UserId OWNER = new UserId("eval-L0-S0-r0");
    static final String SESSION = "cs_1";
    static final String ORDER = "ord_1";
    static final String CREATE_BODY = "{\"line_items\":[{\"item\":{\"id\":\"item_123\"},\"quantity\":2}]}";
    static final String COMPLETE_BODY = "{\"payment\":{\"instrument\":\"pi_eval\"}}";

    String aborted;
    UserId owner = OWNER;
    List<IntentId> proposed = new ArrayList<>(List.of(INTENT));
    IntentState state = IntentState.COMPLETED;
    Optional<String> storedSession = Optional.of(SESSION);
    boolean cancelPending;
    List<IntentEvent> events = new ArrayList<>(List.of(
            transition("PROPOSED", "QUOTING", 10),
            transition("QUOTING", "AWAITING_APPROVAL", 100),
            transition("AWAITING_APPROVAL", "APPROVED", 200),
            transition("APPROVED", "COMPLETING", 250),
            transition("COMPLETING", "COMPLETED", 400)));
    List<MerchantAttempt> attempts = new ArrayList<>(List.of(
            attempt("at-1", AttemptOp.CREATE, 0, key(MutatingOp.CREATE, 0), CREATE_BODY, 20),
            attempt("at-2", AttemptOp.COMPLETE, 0, key(MutatingOp.COMPLETE, 0), COMPLETE_BODY, 255)));
    ApprovalSnapshot snapshot = snapshot(SESSION, 5000, 5000);
    List<AutoApprover.Decision> approvals = new ArrayList<>(List.of(decision(1, snapshot, true, 200)));
    List<ApprovalRecord> approvalRows = new ArrayList<>(List.of(row("ap-1", snapshot, ApprovalStatus.CONSUMED,
            Optional.of(OWNER))));
    List<LogEntry> log = new ArrayList<>(List.of(
            entry(1, 5, "GET", "/.well-known/ucp", Optional.empty(), Optional.empty(), 200),
            entry(2, 20, "POST", "/ucp/v1/checkout-sessions", Optional.of(key(MutatingOp.CREATE, 0)),
                    Optional.of(CREATE_BODY), 201),
            entry(3, 260, "POST", "/ucp/v1/checkout-sessions/" + SESSION + "/complete",
                    Optional.of(key(MutatingOp.COMPLETE, 0)), Optional.of(COMPLETE_BODY), 200)));
    List<GroundTruth.Order> orders = new ArrayList<>(List.of(order(ORDER, SESSION, 5000)));
    Map<String, GroundTruth.Session> sessions = new LinkedHashMap<>(Map.of(SESSION,
            session(SESSION, "completed", "USD", 2, Optional.of(ORDER))));
    Optional<Instant> terminalSeenAt = Optional.of(at(420));

    RunEvidence build() {
        PurchaseIntent intent = new PurchaseIntent(INTENT, OWNER, Scenarios.SHOP, "dedupe-1",
                List.of(new ProposedLine("item_123", 2)), "buyer@eval.example", state, 6,
                storedSession.map(SessionId::new), Optional.of(snapshot.hash()), 0,
                state == IntentState.COMPLETED ? Optional.of(ORDER) : Optional.empty(), Optional.empty(),
                Optional.empty(), cancelPending, Optional.empty(), Optional.empty(), T0, at(400));
        return new RunEvidence("S0", 0, 1000, 0, owner, Optional.ofNullable(aborted), proposed,
                List.of(new RunEvidence.Persisted(intent, attempts, events, approvalRows)), orders, sessions, log,
                approvals, T0, terminalSeenAt);
    }

    static Instant at(long ms) {
        return T0.plusMillis(ms);
    }

    static String key(MutatingOp op, int revision) {
        return IdempotencyKey.derive(INTENT, op, revision).value();
    }

    static IntentEvent transition(String from, String to, long ms) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);
        payload.put("event", "STEP");
        payload.put("guard", "NONE");
        payload.put("to", to);
        return IntentEvent.of("transition", payload, at(ms));
    }

    static MerchantAttempt attempt(String id, AttemptOp op, int revision, String key, String body, long ms) {
        return new MerchantAttempt(new AttemptId(id), INTENT, op, revision, Optional.of(new IdempotencyKey(key)),
                Optional.empty(), Optional.of(new FrozenBody(body)), AttemptStatus.RESPONDED, Optional.of(200),
                Optional.empty(), at(ms), Optional.of(at(ms + 5)));
    }

    static ApprovalSnapshot snapshot(String session, long total, long max) {
        return new ApprovalSnapshot(INTENT, Scenarios.SHOP, new SessionId(session),
                List.of(new SnapshotLine("item_123", 2, total / 2)), "USD", total, max, at(600_000));
    }

    static AutoApprover.Decision decision(int seq, ApprovalSnapshot snap, boolean approved, long ms) {
        return new AutoApprover.Decision(INTENT, seq, snap, snap.hash(), approved, at(ms));
    }

    static ApprovalRecord row(String id, ApprovalSnapshot snap, ApprovalStatus status, Optional<UserId> decidedBy) {
        return new ApprovalRecord(id, INTENT, OWNER, snap, snap.hash(), "token-hash-" + id, snap.expiresAt(), status,
                decidedBy.map(u -> at(200)), decidedBy);
    }

    /** A settled log entry; {@code body} is hashed with the evaluation key as the proxy would. */
    static LogEntry entry(long seq, long ms, String method, String path, Optional<String> key, Optional<String> body,
                          int status) {
        return new LogEntry(seq, at(ms), method, path, Optional.of("req-" + seq), key,
                body.map(b -> I7SameKeySameBody.hmacHex(CONTEXT, b.getBytes(StandardCharsets.UTF_8))), 1,
                OptionalInt.of(status), Optional.empty(), Optional.empty(), false, false, true);
    }

    static LogEntry complete(long seq, long ms, String session, int revision, String body, int status) {
        return entry(seq, ms, "POST", "/ucp/v1/checkout-sessions/" + session + "/complete",
                Optional.of(key(MutatingOp.COMPLETE, revision)), Optional.of(body), status);
    }

    static GroundTruth.Order order(String id, String session, long amount) {
        return new GroundTruth.Order(id, session, Optional.of("buyer@eval.example"), amount);
    }

    static GroundTruth.Session session(String id, String status, String currency, long quantity,
                                       Optional<String> orderId) {
        return new GroundTruth.Session(id, status, currency,
                List.of(new GroundTruth.SessionLine("item_123", quantity, 2500)), 2500 * quantity, orderId);
    }

    private static byte[] key() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 7);
        return k;
    }
}
