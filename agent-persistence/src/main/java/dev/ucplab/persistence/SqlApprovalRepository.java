package dev.ucplab.persistence;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.approval.FulfillmentSnapshot;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.SnapshotLine;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ApprovalRepository} over the {@code approval} table (section 10, 9.2). Never cached (section 20): every call
 * reads the database. User-facing reads filter on {@code user_id} in the SQL (T2, T6).
 *
 * <p>{@code seq} orders an intent's approvals oldest first. It is assigned as {@code MAX(seq) + 1} inside the writing
 * {@code BEGIN IMMEDIATE} transaction, as for attempts and events. The snapshot is stored in the order its lines were
 * given, so a record reads back equal to the one written; the hash is the stored {@code snapshot_hash}, not recomputed.
 */
public final class SqlApprovalRepository implements ApprovalRepository {

    private static final String COLUMNS =
            "id, intent_id, user_id, snapshot_json, snapshot_hash, token_hash, expires_at, status, decided_at, decided_by";

    private final Database db;

    public SqlApprovalRepository(Database db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    @Override
    public void insert(ApprovalRecord a) {
        Objects.requireNonNull(a, "approval");
        db.write(c -> {
            long seq;
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT COALESCE(MAX(seq), 0) + 1 FROM approval WHERE intent_id = ?")) {
                s.setString(1, a.intentId().value());
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    seq = r.getLong(1);
                }
            }
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO approval (id, intent_id, seq, user_id, snapshot_json, snapshot_hash, token_hash,"
                            + " expires_at, status, decided_at, decided_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                s.setString(1, a.id());
                s.setString(2, a.intentId().value());
                s.setLong(3, seq);
                s.setString(4, a.owner().value());
                s.setString(5, snapshotJson(a.snapshot()));
                s.setString(6, a.snapshotHash());
                s.setString(7, a.tokenHash());
                s.setString(8, SqlTimes.format(a.expiresAt()));
                s.setString(9, a.status().name());
                s.setString(10, a.decidedAt().map(SqlTimes::format).orElse(null));
                s.setString(11, a.decidedBy().map(UserId::value).orElse(null));
                s.executeUpdate();
            } catch (SQLException e) {
                if (SqlErrors.isUniqueViolation(e)) {
                    throw new IllegalArgumentException("approval id already exists: " + a.id());
                }
                throw e;
            }
            return null;
        });
    }

    @Override
    public Optional<ApprovalRecord> findLatest(UserId owner, IntentId intentId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(intentId, "intentId");
        return db.read(c -> latest(c,
                "SELECT " + COLUMNS + " FROM approval WHERE intent_id = ? AND user_id = ? ORDER BY seq DESC LIMIT 1",
                intentId.value(), owner.value()));
    }

    @Override
    public Optional<ApprovalRecord> findLatestForSystem(IntentId intentId) {
        Objects.requireNonNull(intentId, "intentId");
        return db.read(c -> latest(c,
                "SELECT " + COLUMNS + " FROM approval WHERE intent_id = ? ORDER BY seq DESC LIMIT 1",
                intentId.value()));
    }

    @Override
    public List<ApprovalRecord> listForSystem(IntentId intentId) {
        Objects.requireNonNull(intentId, "intentId");
        return db.read(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM approval WHERE intent_id = ? ORDER BY seq")) {
                s.setString(1, intentId.value());
                try (ResultSet r = s.executeQuery()) {
                    List<ApprovalRecord> out = new ArrayList<>();
                    while (r.next()) {
                        out.add(record(r));
                    }
                    return List.copyOf(out);
                }
            }
        });
    }

    @Override
    public boolean transition(
            String approvalId, ApprovalStatus expected, ApprovalStatus next, Instant at, Optional<UserId> decidedBy) {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(decidedBy, "decidedBy");
        return db.write(c -> {
            // One conditional UPDATE: the status check and the write are the same statement. A system move, with no
            // decider, keeps the decision already recorded.
            String sql = decidedBy.isPresent()
                    ? "UPDATE approval SET status = ?, decided_at = ?, decided_by = ? WHERE id = ? AND status = ?"
                    : "UPDATE approval SET status = ? WHERE id = ? AND status = ?";
            try (PreparedStatement s = c.prepareStatement(sql)) {
                int i = 1;
                s.setString(i++, next.name());
                if (decidedBy.isPresent()) {
                    s.setString(i++, SqlTimes.format(at));
                    s.setString(i++, decidedBy.get().value());
                }
                s.setString(i++, approvalId);
                s.setString(i, expected.name());
                return s.executeUpdate() == 1;
            }
        });
    }

    private static Optional<ApprovalRecord> latest(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                s.setString(i + 1, params[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(record(r)) : Optional.empty();
            }
        }
    }

    private static ApprovalRecord record(ResultSet r) throws SQLException {
        return new ApprovalRecord(
                r.getString("id"),
                new IntentId(r.getString("intent_id")),
                new UserId(r.getString("user_id")),
                snapshot(r.getString("snapshot_json")),
                r.getString("snapshot_hash"),
                r.getString("token_hash"),
                SqlTimes.parse(r.getString("expires_at")),
                ApprovalStatus.valueOf(r.getString("status")),
                Optional.ofNullable(r.getString("decided_at")).map(SqlTimes::parse),
                Optional.ofNullable(r.getString("decided_by")).map(UserId::new));
    }

    /** The snapshot as stored: lines in the order given (the canonical, sorted form is only for hashing). */
    private static String snapshotJson(ApprovalSnapshot s) {
        List<Object> lines = new ArrayList<>();
        for (SnapshotLine l : s.lines()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("itemId", l.itemId());
            line.put("quantity", l.quantity());
            line.put("unitPriceMinor", l.unitPriceMinor());
            lines.add(line);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intentId", s.intentId().value());
        m.put("merchantId", s.merchantId().value());
        m.put("sessionId", s.sessionId().value());
        m.put("lines", lines);
        m.put("currency", s.currency());
        m.put("totalMinor", s.totalMinor());
        m.put("maxTotalMinor", s.maxTotalMinor());
        m.put("expiresAt", SqlTimes.format(s.expiresAt()));
        s.fulfillment().ifPresent(f -> {
            Map<String, Object> shipping = new LinkedHashMap<>();
            shipping.put("optionId", f.optionId());
            shipping.put("label", f.label());
            shipping.put("priceMinor", f.priceMinor());
            f.destinationCountry().ifPresent(c -> shipping.put("destinationCountry", c));
            m.put("fulfillment", shipping);
        });
        return CanonicalJson.write(m);
    }

    @SuppressWarnings("unchecked")
    static ApprovalSnapshot snapshot(String json) {
        Map<String, Object> m = CanonicalJson.readObject(json);
        List<SnapshotLine> lines = new ArrayList<>();
        for (Object o : (List<Object>) m.get("lines")) {
            Map<String, Object> l = (Map<String, Object>) o;
            lines.add(new SnapshotLine((String) l.get("itemId"), ((Long) l.get("quantity")).intValue(),
                    (Long) l.get("unitPriceMinor")));
        }
        return new ApprovalSnapshot(
                new IntentId((String) m.get("intentId")), new MerchantId((String) m.get("merchantId")),
                new SessionId((String) m.get("sessionId")), lines, (String) m.get("currency"),
                (Long) m.get("totalMinor"), (Long) m.get("maxTotalMinor"), SqlTimes.parse((String) m.get("expiresAt")),
                m.get("fulfillment") instanceof Map<?, ?> f
                        ? Optional.of(new FulfillmentSnapshot((String) f.get("optionId"), (String) f.get("label"),
                                (Long) f.get("priceMinor"), Optional.ofNullable((String) f.get("destinationCountry"))))
                        : Optional.empty());
    }
}
