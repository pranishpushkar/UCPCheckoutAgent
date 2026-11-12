package dev.ucplab.persistence;

import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.RecoveryScan;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link RecoveryScan} over the database (7.6): intents still in AWAITING_APPROVAL whose newest approval is PENDING and
 * past its {@code expires_at}, or already EXPIRED, and whose lease is free. A system read: it takes no lease and
 * caches nothing (section 20).
 */
public final class SqlRecoveryScan implements RecoveryScan {

    private final Database db;

    public SqlRecoveryScan(Database db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    @Override
    public List<IntentId> approvalsDue(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
        String nowText = SqlTimes.format(now);
        return db.read(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT i.id FROM purchase_intent i JOIN approval a ON a.intent_id = i.id"
                            + " WHERE i.state = 'AWAITING_APPROVAL'"
                            + " AND (i.lease_until IS NULL OR i.lease_until < ?)"
                            + " AND a.seq = (SELECT MAX(seq) FROM approval WHERE intent_id = i.id)"
                            + " AND (a.status = 'EXPIRED' OR (a.status = 'PENDING' AND a.expires_at <= ?))"
                            + " ORDER BY a.expires_at, i.id LIMIT ?")) {
                s.setString(1, nowText);
                s.setString(2, nowText);
                s.setInt(3, limit);
                try (ResultSet r = s.executeQuery()) {
                    List<IntentId> out = new ArrayList<>();
                    while (r.next()) {
                        out.add(new IntentId(r.getString(1)));
                    }
                    return List.copyOf(out);
                }
            }
        });
    }
}
