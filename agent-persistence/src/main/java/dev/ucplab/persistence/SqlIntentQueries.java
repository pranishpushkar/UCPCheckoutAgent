package dev.ucplab.persistence;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentCursor;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentPage;
import dev.ucplab.core.intent.IntentQueries;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link IntentQueries} over {@code purchase_intent} and {@code intent_event} (section 10, 23.2). Never cached
 * (section 20). Each read is one statement that filters on {@code user_id} in the SQL (T2, T6), so the owner check and
 * the read cannot drift apart. Paging uses {@code ix_intent_user (user_id, created_at)}; fixed-width times (section 10)
 * make text order time order, and the id breaks a tie.
 */
public final class SqlIntentQueries implements IntentQueries {

    private final Database db;

    public SqlIntentQueries(Database db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    @Override
    public IntentPage page(UserId owner, Optional<IntentState> state, Optional<IntentCursor> after, int limit) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(after, "after");
        IntentPage.checkLimit(limit);
        StringBuilder sql = new StringBuilder("SELECT " + SqlIntentRepository.INTENT_COLUMNS
                + " FROM purchase_intent WHERE user_id = ?");
        state.ifPresent(s -> sql.append(" AND state = ?"));
        after.ifPresent(c -> sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))"));
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        List<PurchaseIntent> rows = db.read(c -> {
            try (PreparedStatement s = c.prepareStatement(sql.toString())) {
                int i = 1;
                s.setString(i++, owner.value());
                if (state.isPresent()) {
                    s.setString(i++, state.get().name());
                }
                if (after.isPresent()) {
                    String at = SqlTimes.format(after.get().createdAt());
                    s.setString(i++, at);
                    s.setString(i++, at);
                    s.setString(i++, after.get().id().value());
                }
                // One more than the page, to learn whether a next page exists without a second query.
                s.setInt(i, limit + 1);
                try (ResultSet r = s.executeQuery()) {
                    List<PurchaseIntent> out = new ArrayList<>();
                    while (r.next()) {
                        out.add(SqlIntentRepository.intent(r));
                    }
                    return out;
                }
            }
        });
        if (rows.size() <= limit) {
            return new IntentPage(rows, Optional.empty());
        }
        List<PurchaseIntent> page = rows.subList(0, limit);
        return new IntentPage(page, Optional.of(IntentCursor.after(page.get(limit - 1))));
    }

    @Override
    public List<IntentEvent> events(UserId owner, IntentId id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        return db.read(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT e.type, e.payload_json, e.at FROM intent_event e"
                    + " JOIN purchase_intent i ON i.id = e.intent_id WHERE e.intent_id = ? AND i.user_id = ?"
                    + " ORDER BY e.seq")) {
                s.setString(1, id.value());
                s.setString(2, owner.value());
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
}
