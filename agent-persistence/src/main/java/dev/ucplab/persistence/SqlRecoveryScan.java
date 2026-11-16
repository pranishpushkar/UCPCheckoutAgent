package dev.ucplab.persistence;

import dev.ucplab.core.intent.InFlightMerchants;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.RecoveryScan;
import dev.ucplab.core.merchant.MerchantId;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link RecoveryScan} over the database (7.6): intents still in AWAITING_APPROVAL whose newest approval is PENDING and
 * past its {@code expires_at}, or already EXPIRED, and whose lease is free. A system read: it takes no lease and
 * caches nothing (section 20).
 */
public final class SqlRecoveryScan implements RecoveryScan, InFlightMerchants {

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

    /**
     * By merchant, the distinct fingerprints of intents in a non-terminal state or owing a cancel, across owners: a system
     * read, like the one above.
     */
    @Override
    public Map<MerchantId, Set<String>> fingerprintsInFlight() {
        String terminal = Arrays.stream(IntentState.values()).filter(IntentState::isTerminal)
                .map(state -> "'" + state.name() + "'").collect(Collectors.joining(", "));
        return db.read(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT DISTINCT merchant_id, merchant_fingerprint FROM purchase_intent"
                            + " WHERE state NOT IN (" + terminal + ") OR cancel_pending = 1");
                 ResultSet r = s.executeQuery()) {
                Map<MerchantId, Set<String>> out = new HashMap<>();
                while (r.next()) {
                    out.computeIfAbsent(new MerchantId(r.getString(1)), m -> new HashSet<>()).add(r.getString(2));
                }
                Map<MerchantId, Set<String>> copy = new HashMap<>();
                out.forEach((m, f) -> copy.put(m, Set.copyOf(f)));
                return Map.copyOf(copy);
            }
        });
    }
}
