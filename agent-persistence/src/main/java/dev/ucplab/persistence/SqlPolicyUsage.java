package dev.ucplab.persistence;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.policy.PolicyUsage;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@link PolicyUsage} over {@code purchase_intent} and {@code approval} (9.1 P3 and P5). Read live on every evaluation,
 * never cached (section 20), and keyed by the user in the SQL (T5). Windows are {@code [from, to)} on the intent's
 * {@code created_at}.
 *
 * <p><b>P3.</b> The sum of the {@code totalMinor} of each counted intent's newest approval (the highest {@code seq}),
 * so a re-quoted intent counts once, at its latest quote. A counted intent with no approval adds nothing. The amount is
 * read from {@code snapshot_json} in Java, because extracting JSON in SQL is not portable (section 10).
 *
 * <p><b>P5.</b> Every intent the user created in the window, whatever its state: a proposal the policy refused is still
 * a proposal, so a looping agent stays limited.
 *
 * <p>The check and the insert are separate calls (21.3's single transaction is left for M7, m6-plan Decision 4); in M6
 * one active run per user keeps the agent from racing its own proposals.
 */
public final class SqlPolicyUsage implements PolicyUsage {

    /** The 9.1 P3 states: spend that is committed or may have happened. PROPOSED and QUOTING (a re-quote) are not. */
    static final List<IntentState> SPEND_STATES = List.of(IntentState.AWAITING_APPROVAL, IntentState.APPROVED,
            IntentState.COMPLETING, IntentState.RECONCILING, IntentState.COMPLETED, IntentState.ESCALATED);

    private static final String SPEND_FILTER = SPEND_STATES.stream()
            .map(s -> "'" + s.name() + "'").collect(Collectors.joining(", ", "i.state IN (", ")"));

    private final Database db;

    public SqlPolicyUsage(Database db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    @Override
    public long committedSpendMinor(UserId user, Instant from, Instant to) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return db.read(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT a.snapshot_json FROM purchase_intent i"
                    + " JOIN approval a ON a.intent_id = i.id"
                    + " WHERE i.user_id = ? AND " + SPEND_FILTER + " AND i.created_at >= ? AND i.created_at < ?"
                    + " AND a.seq = (SELECT MAX(seq) FROM approval WHERE intent_id = i.id)")) {
                s.setString(1, user.value());
                s.setString(2, SqlTimes.format(from));
                s.setString(3, SqlTimes.format(to));
                try (ResultSet r = s.executeQuery()) {
                    long total = 0;
                    while (r.next()) {
                        total = Math.addExact(total, SqlApprovalRepository.snapshot(r.getString(1)).totalMinor());
                    }
                    return total;
                }
            }
        });
    }

    @Override
    public int proposalCount(UserId user, Instant from, Instant to) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return db.read(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM purchase_intent"
                    + " WHERE user_id = ? AND created_at >= ? AND created_at < ?")) {
                s.setString(1, user.value());
                s.setString(2, SqlTimes.format(from));
                s.setString(3, SqlTimes.format(to));
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    return r.getInt(1);
                }
            }
        });
    }
}
