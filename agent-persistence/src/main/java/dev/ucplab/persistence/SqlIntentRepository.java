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
import dev.ucplab.core.intent.LeaseLostException;
import dev.ucplab.core.intent.StaleVersionException;
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
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link IntentRepository} over {@code purchase_intent}, {@code intent_event} and {@code merchant_attempt} (section 10).
 * Never cached (section 20). Every user-facing read filters on {@code user_id} in the SQL (T2, T6).
 *
 * <p>Every write is one {@code BEGIN IMMEDIATE} transaction of {@link Database#write}. Fenced writes put id, lease owner
 * and version in one WHERE (7.6); when it matches nothing the row is read again in the same transaction and the failure
 * is, in the in-memory adapter's order, not found, lease lost, then stale version. A write-ahead (7.3 steps 3 and 5)
 * changes the intent row and the attempt row in one transaction, so neither is visible without the other.
 */
public final class SqlIntentRepository implements IntentRepository {

    static final String INTENT_COLUMNS = "id, user_id, merchant_id, dedupe_key, lines_json, buyer_email, state,"
            + " version, session_id, approved_snapshot_hash, requote_count, order_id, failure_reason, uncertain_since,"
            + " cancel_pending, lease_owner, lease_until, created_at, updated_at";

    private static final String ATTEMPT_COLUMNS = "id, intent_id, op, revision, idempotency_key, request_hash,"
            + " request_body, status, http_status, response_json, sent_at, responded_at";

    /** The states the partial unique index excludes (V1, 7.7); built from the enum so the two cannot drift apart in Java. */
    private static final String ACTIVE_STATE_FILTER = "state NOT IN ("
            + Arrays.stream(IntentState.values()).filter(IntentState::isTerminal)
                    .map(s -> "'" + s.name() + "'").collect(Collectors.joining(", "))
            + ")";

    /** The states a worker drives, and any intent that still owes a cancel (7.6). */
    private static final String RECOVERABLE_FILTER = "state IN ('QUOTING', 'APPROVED', 'COMPLETING', 'RECONCILING')"
            + " OR cancel_pending = 1";

    /** Lease free for {@code owner} at {@code now}: held by owner, never taken, or expired. Binds owner, then now. */
    private static final String LEASE_FREE = "(lease_owner = ? OR lease_until IS NULL OR lease_until < ?)";

    private final Database db;
    private final Clock clock;
    private final Failpoint failpoint;

    public SqlIntentRepository(Database db, Clock clock) {
        this(db, clock, Failpoint.NONE);
    }

    /** With a test seam that can fail a write between its statements (crash-matrix row 20). */
    public SqlIntentRepository(Database db, Clock clock, Failpoint failpoint) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.failpoint = Objects.requireNonNull(failpoint, "failpoint");
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

    @Override
    public void update(PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        db.write(c -> {
            Optional<String> session = storedSession(c, intent.id());
            fencedUpdate(c, intent, expectedVersion, leaseOwner);
            requireSessionUnchanged(c, intent, session);
            return null;
        });
    }

    @Override
    public void recordAttempt(MerchantAttempt attempt, PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        if (!attempt.intentId().equals(intent.id())) {
            throw new IllegalArgumentException("attempt " + attempt.id() + " belongs to another intent");
        }
        // 7.3 step 3: the intent row and the attempt row are one BEGIN IMMEDIATE transaction. Any exception, including
        // the failpoint's, rolls both back.
        db.write(c -> {
            Optional<String> session = storedSession(c, intent.id());
            fencedUpdate(c, intent, expectedVersion, leaseOwner);
            failpoint.hit(Failpoint.RECORD_ATTEMPT_BETWEEN);
            insertAttempt(c, attempt);
            requireSessionUnchanged(c, intent, session);
            failpoint.hit(Failpoint.RECORD_ATTEMPT_AFTER);
            return null;
        });
    }

    @Override
    public void resolveAttempt(
            AttemptId id, AttemptStatus status, Optional<Integer> httpStatus, Optional<String> responseJson) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(responseJson, "responseJson");
        db.write(c -> {
            if (updateAttempt(c, id, null, status, httpStatus, responseJson) == 0) {
                throw new IntentNotFoundException("no such attempt: " + id);
            }
            return null;
        });
    }

    @Override
    public void resolveAttempt(
            AttemptId id, AttemptStatus status, Optional<Integer> httpStatus, Optional<String> responseJson,
            PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(responseJson, "responseJson");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        // 7.3 step 5: one transaction, under the same fencing as step 3.
        db.write(c -> {
            Optional<String> session = storedSession(c, intent.id());
            fencedUpdate(c, intent, expectedVersion, leaseOwner);
            Optional<String> owner = attemptIntent(c, id);
            if (owner.isEmpty()) {
                throw new IntentNotFoundException("no such attempt: " + id);
            }
            if (!owner.get().equals(intent.id().value())) {
                throw new IllegalArgumentException("attempt " + id + " belongs to another intent");
            }
            failpoint.hit(Failpoint.RESOLVE_ATTEMPT_BETWEEN);
            updateAttempt(c, id, intent.id(), status, httpStatus, responseJson);
            requireSessionUnchanged(c, intent, session);
            failpoint.hit(Failpoint.RESOLVE_ATTEMPT_AFTER);
            return null;
        });
    }

    @Override
    public void resolveAttempts(
            IntentId intentId, List<AttemptId> ids, AttemptStatus status, Optional<Integer> httpStatus,
            Optional<String> responseJson, long expectedVersion, String leaseOwner) {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(responseJson, "responseJson");
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        db.write(c -> {
            // The fence is a conditional UPDATE that changes nothing: the intent and its version stay as they are.
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE purchase_intent SET version = version WHERE id = ? AND lease_owner = ? AND version = ?")) {
                s.setString(1, intentId.value());
                s.setString(2, leaseOwner);
                s.setLong(3, expectedVersion);
                if (s.executeUpdate() == 0) {
                    throw fenceFailure(c, intentId, null, expectedVersion, leaseOwner);
                }
            }
            // Check every attempt before the first write, so a refusal leaves nothing changed.
            for (AttemptId id : ids) {
                Optional<String> owner = attemptIntent(c, id);
                if (owner.isEmpty()) {
                    throw new IntentNotFoundException("no such attempt: " + id);
                }
                if (!owner.get().equals(intentId.value())) {
                    throw new IllegalArgumentException("attempt " + id + " belongs to another intent");
                }
            }
            for (AttemptId id : ids) {
                updateAttempt(c, id, intentId, status, httpStatus, responseJson);
            }
            return null;
        });
    }

    @Override
    public Optional<PurchaseIntent> claim(IntentId id, String owner, Duration lease) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(lease, "lease");
        return db.write(c -> {
            Instant now = clock.instant();
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE purchase_intent SET lease_owner = ?, lease_until = ? WHERE id = ? AND " + LEASE_FREE)) {
                s.setString(1, owner);
                s.setString(2, SqlTimes.format(now.plus(lease)));
                s.setString(3, id.value());
                s.setString(4, owner);
                s.setString(5, SqlTimes.format(now));
                if (s.executeUpdate() == 0) {
                    return Optional.empty();
                }
            }
            return one(c, "SELECT " + INTENT_COLUMNS + " FROM purchase_intent WHERE id = ?", id.value());
        });
    }

    @Override
    public List<PurchaseIntent> claimRecoverable(
            String owner, Duration lease, int limit, Set<IntentId> exclude) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(exclude, "exclude");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
        return db.write(c -> {
            Instant now = clock.instant();
            String nowText = SqlTimes.format(now);
            String untilText = SqlTimes.format(now.plus(lease));
            List<String> ids = new ArrayList<>();
            List<String> excluded = exclude.stream().map(IntentId::value).toList();
            // Excluded intents are left out in the SQL, so they do not count against the limit.
            String notExcluded = excluded.isEmpty() ? ""
                    : " AND id NOT IN (" + String.join(", ", java.util.Collections.nCopies(excluded.size(), "?")) + ")";
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT id FROM purchase_intent WHERE (" + RECOVERABLE_FILTER + ") AND " + LEASE_FREE + notExcluded
                            + " ORDER BY created_at, id LIMIT ?")) {
                int n = 1;
                s.setString(n++, owner);
                s.setString(n++, nowText);
                for (String id : excluded) {
                    s.setString(n++, id);
                }
                s.setInt(n, limit);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        ids.add(r.getString(1));
                    }
                }
            }
            // BEGIN IMMEDIATE holds the write lock from the select to here, so no other worker can take one of these
            // in between; the UPDATE repeats the lease condition anyway, as the one conditional statement that claims.
            List<PurchaseIntent> claimed = new ArrayList<>();
            for (String id : ids) {
                try (PreparedStatement s = c.prepareStatement(
                        "UPDATE purchase_intent SET lease_owner = ?, lease_until = ? WHERE id = ? AND " + LEASE_FREE)) {
                    s.setString(1, owner);
                    s.setString(2, untilText);
                    s.setString(3, id);
                    s.setString(4, owner);
                    s.setString(5, nowText);
                    if (s.executeUpdate() == 1) {
                        one(c, "SELECT " + INTENT_COLUMNS + " FROM purchase_intent WHERE id = ?", id)
                                .ifPresent(claimed::add);
                    }
                }
            }
            return List.copyOf(claimed);
        });
    }

    @Override
    public boolean renewLease(IntentId id, String owner, Duration lease) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(lease, "lease");
        return db.write(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE purchase_intent SET lease_until = ? WHERE id = ? AND lease_owner = ?")) {
                s.setString(1, SqlTimes.format(clock.instant().plus(lease)));
                s.setString(2, id.value());
                s.setString(3, owner);
                return s.executeUpdate() == 1;
            }
        });
    }

    /** The fenced write of 7.6: id, lease owner and version in one WHERE; on no match, the reason (lease, then version). */
    private static void fencedUpdate(Connection c, PurchaseIntent i, long expectedVersion, String leaseOwner)
            throws SQLException {
        // Only the mutable columns: owner, merchant, dedupe key, lines, buyer, creation time and the lease are not
        // taken from the caller's copy.
        try (PreparedStatement s = c.prepareStatement(
                "UPDATE purchase_intent SET state = ?, version = ?, session_id = ?, approved_snapshot_hash = ?,"
                        + " requote_count = ?, order_id = ?, failure_reason = ?, uncertain_since = ?,"
                        + " cancel_pending = ?, updated_at = ? WHERE id = ? AND lease_owner = ? AND version = ?"
                        + " AND merchant_id = ?")) {
            int n = 1;
            s.setString(n++, i.state().name());
            s.setLong(n++, expectedVersion + 1);
            s.setString(n++, i.sessionId().map(SessionId::value).orElse(null));
            s.setString(n++, i.approvedSnapshotHash().orElse(null));
            s.setInt(n++, i.requoteCount());
            s.setString(n++, i.orderId().orElse(null));
            s.setString(n++, i.failureReason().orElse(null));
            s.setString(n++, i.uncertainSince().map(SqlTimes::format).orElse(null));
            s.setInt(n++, i.cancelPending() ? 1 : 0);
            s.setString(n++, SqlTimes.format(i.updatedAt()));
            s.setString(n++, i.id().value());
            s.setString(n++, leaseOwner);
            s.setLong(n++, expectedVersion);
            s.setString(n, i.merchantId().value());
            if (s.executeUpdate() == 0) {
                throw fenceFailure(c, i.id(), i.merchantId(), expectedVersion, leaseOwner);
            }
        }
    }

    /**
     * Re-reads the row in the writing transaction to say why the fenced write matched nothing: not found, another
     * merchant (I11, only when {@code merchant} is given), lease, version. The merchant is checked first, because a
     * caller that names another merchant is wrong whatever the lease and the version are.
     */
    private static RuntimeException fenceFailure(Connection c, IntentId id, MerchantId merchant, long expectedVersion,
                                                 String leaseOwner) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT lease_owner, version, merchant_id FROM purchase_intent WHERE id = ?")) {
            s.setString(1, id.value());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    return new IntentNotFoundException("no such intent: " + id);
                }
                if (merchant != null && !merchant.value().equals(r.getString(3))) {
                    return new IllegalArgumentException("I11: the merchant of intent " + id + " cannot change");
                }
                if (!leaseOwner.equals(r.getString(1))) {
                    return new LeaseLostException(id, leaseOwner);
                }
                return new StaleVersionException(id, expectedVersion, r.getLong(2));
            }
        }
    }

    private static Optional<String> storedSession(Connection c, IntentId id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT session_id FROM purchase_intent WHERE id = ?")) {
            s.setString(1, id.value());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.ofNullable(r.getString(1)) : Optional.empty();
            }
        }
    }

    /**
     * I4: once a COMPLETE attempt exists, the session id cannot change. Run after the writes of the same transaction,
     * so a COMPLETE attempt recorded together with the change counts; the exception rolls them back.
     */
    private static void requireSessionUnchanged(Connection c, PurchaseIntent update, Optional<String> storedSession)
            throws SQLException {
        if (storedSession.isEmpty() || storedSession.equals(update.sessionId().map(SessionId::value))) {
            return;
        }
        try (PreparedStatement s = c.prepareStatement(
                "SELECT 1 FROM merchant_attempt WHERE intent_id = ? AND op = 'COMPLETE'")) {
            s.setString(1, update.id().value());
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    throw new IllegalStateException("I4: session of intent " + update.id()
                            + " cannot change after a complete attempt");
                }
            }
        }
    }

    private static void insertAttempt(Connection c, MerchantAttempt a) throws SQLException {
        // BEGIN IMMEDIATE makes this the only writer, so MAX + 1 cannot collide; UNIQUE (intent_id, seq) is the backstop.
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO merchant_attempt (" + ATTEMPT_COLUMNS + ", seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " (SELECT COALESCE(MAX(seq), 0) + 1 FROM merchant_attempt WHERE intent_id = ?))")) {
            int n = 1;
            s.setString(n++, a.id().value());
            s.setString(n++, a.intentId().value());
            s.setString(n++, a.op().name());
            s.setInt(n++, a.revision());
            s.setString(n++, a.idempotencyKey().map(IdempotencyKey::value).orElse(null));
            s.setString(n++, a.requestHash().orElse(null));
            s.setString(n++, a.requestBody().map(FrozenBody::json).orElse(null));
            s.setString(n++, a.status().name());
            if (a.httpStatus().isPresent()) {
                s.setInt(n++, a.httpStatus().get());
            } else {
                s.setNull(n++, Types.INTEGER);
            }
            s.setString(n++, a.responseJson().orElse(null));
            s.setString(n++, SqlTimes.format(a.sentAt()));
            s.setString(n++, a.respondedAt().map(SqlTimes::format).orElse(null));
            s.setString(n, a.intentId().value());
            s.executeUpdate();
        } catch (SQLException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                // Naming the id is right: under BEGIN IMMEDIATE no other writer can take (intent_id, seq) between the
                // MAX and the insert, so a unique violation here means a repeated attempt id, a real bug, not a race.
                throw new IllegalArgumentException("attempt id already exists: " + a.id());
            }
            throw e;
        }
    }

    private static Optional<String> attemptIntent(Connection c, AttemptId id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT intent_id FROM merchant_attempt WHERE id = ?")) {
            s.setString(1, id.value());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
        }
    }

    /** Settles one attempt, keeping its frozen body and key. {@code intent} narrows the match when it is not null. */
    private int updateAttempt(
            Connection c, AttemptId id, IntentId intent, AttemptStatus status, Optional<Integer> httpStatus,
            Optional<String> responseJson) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "UPDATE merchant_attempt SET status = ?, http_status = ?, response_json = ?, responded_at = ?"
                        + " WHERE id = ?" + (intent == null ? "" : " AND intent_id = ?"))) {
            s.setString(1, status.name());
            if (httpStatus.isPresent()) {
                s.setInt(2, httpStatus.get());
            } else {
                s.setNull(2, Types.INTEGER);
            }
            s.setString(3, responseJson.orElse(null));
            s.setString(4, SqlTimes.format(clock.instant()));
            s.setString(5, id.value());
            if (intent != null) {
                s.setString(6, intent.value());
            }
            return s.executeUpdate();
        }
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

    static PurchaseIntent intent(ResultSet r) throws SQLException {
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
