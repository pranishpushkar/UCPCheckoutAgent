package dev.ucplab.persistence;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.AttemptId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.DuplicateIntentException;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentNotFoundException;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link IntentRepository} over {@code purchase_intent}, {@code intent_event} and {@code merchant_attempt} (section 10).
 * Never cached (section 20). Every user-facing read filters on {@code user_id} in the SQL (T2, T6).
 *
 * <p>This class has the reads, the insert and the event log. The claim, fenced-write and attempt methods are not
 * written yet and throw {@link UnsupportedOperationException}.
 */
public final class SqlIntentRepository implements IntentRepository {

    private static final String INTENT_COLUMNS = "id, user_id, merchant_id, dedupe_key, lines_json, buyer_email, state,"
            + " version, session_id, approved_snapshot_hash, requote_count, order_id, failure_reason, uncertain_since,"
            + " cancel_pending, lease_owner, lease_until, created_at, updated_at";

    private static final String ATTEMPT_COLUMNS = "id, intent_id, op, revision, idempotency_key, request_hash,"
            + " request_body, status, http_status, response_json, sent_at, responded_at";

    /** The states the partial unique index excludes (V1, 7.7); built from the enum so the two cannot drift apart in Java. */
    private static final String ACTIVE_STATE_FILTER = "state NOT IN ("
            + Arrays.stream(IntentState.values()).filter(IntentState::isTerminal)
                    .map(s -> "'" + s.name() + "'").collect(Collectors.joining(", "))
            + ")";

    private final Database db;
    // Read by the lease methods, which are not written yet.
    private final Clock clock;

    public SqlIntentRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<PurchaseIntent> find(UserId owner, IntentId id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        return db.read(c -> one(c,
                "SELECT " + INTENT_COLUMNS + " FROM purchase_intent WHERE id = ? AND user_id = ?",
                id.value(), owner.value()));
    }

    @Override
    public Optional<PurchaseIntent> findForSystem(IntentId id) {
        Objects.requireNonNull(id, "id");
        return db.read(c -> one(c, "SELECT " + INTENT_COLUMNS + " FROM purchase_intent WHERE id = ?", id.value()));
    }

    @Override
    public Optional<PurchaseIntent> findActiveByDedupeKey(String dedupeKey) {
        Objects.requireNonNull(dedupeKey, "dedupeKey");
        return db.read(c -> one(c,
                "SELECT " + INTENT_COLUMNS + " FROM purchase_intent WHERE dedupe_key = ? AND " + ACTIVE_STATE_FILTER,
                dedupeKey));
    }

    @Override
    public void insert(PurchaseIntent i) {
        Objects.requireNonNull(i, "intent");
        db.write(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO purchase_intent (" + INTENT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " ?, ?, ?, ?, ?, ?)")) {
                int n = 1;
                s.setString(n++, i.id().value());
                s.setString(n++, i.owner().value());
                s.setString(n++, i.merchantId().value());
                s.setString(n++, i.dedupeKey());
                s.setString(n++, linesJson(i.lines()));
                s.setString(n++, i.buyerEmail());
                s.setString(n++, i.state().name());
                s.setLong(n++, i.version());
                s.setString(n++, i.sessionId().map(SessionId::value).orElse(null));
                s.setString(n++, i.approvedSnapshotHash().orElse(null));
                s.setInt(n++, i.requoteCount());
                s.setString(n++, i.orderId().orElse(null));
                s.setString(n++, i.failureReason().orElse(null));
                s.setString(n++, i.uncertainSince().map(SqlTimes::format).orElse(null));
                s.setInt(n++, i.cancelPending() ? 1 : 0);
                s.setString(n++, i.leaseOwner().orElse(null));
                s.setString(n++, i.leaseUntil().map(SqlTimes::format).orElse(null));
                s.setString(n++, SqlTimes.format(i.createdAt()));
                s.setString(n, SqlTimes.format(i.updatedAt()));
                s.executeUpdate();
            } catch (SQLException e) {
                // The primary key (a repeated id) and ux_intent_active_dedupe (an active dedupe key) both land here.
                if (SqlErrors.isUniqueViolation(e)) {
                    throw new DuplicateIntentException("intent " + i.id() + " repeats an id or an active dedupe key");
                }
                throw e;
            }
            return null;
        });
    }

    @Override
    public void appendEvent(IntentId id, IntentEvent event) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(event, "event");
        db.write(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM purchase_intent WHERE id = ?")) {
                s.setString(1, id.value());
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) {
                        throw new IntentNotFoundException("no such intent: " + id);
                    }
                }
            }
            // BEGIN IMMEDIATE makes this the only writer, so MAX + 1 cannot collide; the primary key is the backstop.
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO intent_event (intent_id, seq, type, payload_json, at) VALUES (?,"
                            + " (SELECT COALESCE(MAX(seq), 0) + 1 FROM intent_event WHERE intent_id = ?), ?, ?, ?)")) {
                s.setString(1, id.value());
                s.setString(2, id.value());
                s.setString(3, event.type());
                s.setString(4, event.payloadJson());
                s.setString(5, SqlTimes.format(event.at()));
                s.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<MerchantAttempt> attempts(IntentId id) {
        Objects.requireNonNull(id, "id");
        return db.read(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT " + ATTEMPT_COLUMNS + " FROM merchant_attempt WHERE intent_id = ? ORDER BY seq")) {
                s.setString(1, id.value());
                try (ResultSet r = s.executeQuery()) {
                    List<MerchantAttempt> out = new ArrayList<>();
                    while (r.next()) {
                        out.add(attempt(r));
                    }
                    return List.copyOf(out);
                }
            }
        });
    }

    /** Test and harness view of an intent's event log, in sequence order. */
    public List<IntentEvent> events(IntentId id) {
        Objects.requireNonNull(id, "id");
        return db.read(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT type, payload_json, at FROM intent_event WHERE intent_id = ? ORDER BY seq")) {
                s.setString(1, id.value());
                try (ResultSet r = s.executeQuery()) {
                    List<IntentEvent> out = new ArrayList<>();
                    while (r.next()) {
                        out.add(new IntentEvent(r.getString(1), r.getString(2), SqlTimes.parse(r.getString(3))));
                    }
                    return List.copyOf(out);
                }
            }
        });
    }

    // Not written yet: claims, fencing and the atomic write-ahead.

    @Override
    public void update(PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        throw notYet();
    }

    @Override
    public void recordAttempt(MerchantAttempt attempt, PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        throw notYet();
    }

    @Override
    public void resolveAttempt(
            AttemptId id, AttemptStatus status, Optional<Integer> httpStatus, Optional<String> responseJson) {
        throw notYet();
    }

    @Override
    public void resolveAttempt(
            AttemptId id, AttemptStatus status, Optional<Integer> httpStatus, Optional<String> responseJson,
            PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        throw notYet();
    }

    @Override
    public void resolveAttempts(
            IntentId intentId, List<AttemptId> ids, AttemptStatus status, Optional<Integer> httpStatus,
            Optional<String> responseJson, long expectedVersion, String leaseOwner) {
        throw notYet();
    }

    @Override
    public Optional<PurchaseIntent> claim(IntentId id, String owner, Duration lease) {
        throw notYet();
    }

    @Override
    public List<PurchaseIntent> claimRecoverable(String owner, Duration lease, int limit) {
        throw notYet();
    }

    @Override
    public boolean renewLease(IntentId id, String owner, Duration lease) {
        throw notYet();
    }

    private UnsupportedOperationException notYet() {
        return new UnsupportedOperationException("not implemented in SqlIntentRepository yet");
    }

    private static Optional<PurchaseIntent> one(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                s.setString(i + 1, params[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(intent(r)) : Optional.empty();
            }
        }
    }

    private static PurchaseIntent intent(ResultSet r) throws SQLException {
        return new PurchaseIntent(
                new IntentId(r.getString("id")),
                new UserId(r.getString("user_id")),
                new MerchantId(r.getString("merchant_id")),
                r.getString("dedupe_key"),
                lines(r.getString("lines_json")),
                r.getString("buyer_email"),
                IntentState.valueOf(r.getString("state")),
                r.getLong("version"),
                Optional.ofNullable(r.getString("session_id")).map(SessionId::new),
                Optional.ofNullable(r.getString("approved_snapshot_hash")),
                r.getInt("requote_count"),
                Optional.ofNullable(r.getString("order_id")),
                Optional.ofNullable(r.getString("failure_reason")),
                Optional.ofNullable(r.getString("uncertain_since")).map(SqlTimes::parse),
                r.getInt("cancel_pending") != 0,
                Optional.ofNullable(r.getString("lease_owner")),
                Optional.ofNullable(r.getString("lease_until")).map(SqlTimes::parse),
                SqlTimes.parse(r.getString("created_at")),
                SqlTimes.parse(r.getString("updated_at")));
    }

    private static MerchantAttempt attempt(ResultSet r) throws SQLException {
        int http = r.getInt("http_status");
        Optional<Integer> httpStatus = r.wasNull() ? Optional.empty() : Optional.of(http);
        return new MerchantAttempt(
                new AttemptId(r.getString("id")),
                new IntentId(r.getString("intent_id")),
                AttemptOp.valueOf(r.getString("op")),
                r.getInt("revision"),
                Optional.ofNullable(r.getString("idempotency_key")).map(IdempotencyKey::new),
                Optional.ofNullable(r.getString("request_hash")),
                Optional.ofNullable(r.getString("request_body")).map(FrozenBody::new),
                AttemptStatus.valueOf(r.getString("status")),
                httpStatus,
                Optional.ofNullable(r.getString("response_json")),
                SqlTimes.parse(r.getString("sent_at")),
                Optional.ofNullable(r.getString("responded_at")).map(SqlTimes::parse));
    }

    /** The proposal's lines, kept in the order given: a create resent after a crash is rebuilt from them (7.4). */
    private static String linesJson(List<ProposedLine> lines) {
        List<Object> out = new ArrayList<>();
        for (ProposedLine l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", l.itemId());
            m.put("quantity", l.quantity());
            out.add(m);
        }
        return CanonicalJson.write(out);
    }

    @SuppressWarnings("unchecked")
    private static List<ProposedLine> lines(String json) {
        List<ProposedLine> out = new ArrayList<>();
        for (Object o : (List<Object>) CanonicalJson.read(json)) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.add(new ProposedLine((String) m.get("itemId"), ((Long) m.get("quantity")).intValue()));
        }
        return out;
    }
}
