package dev.ucplab.persistence;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.agent.RunRepository;
import dev.ucplab.core.agent.ToolEvent;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link RunRepository} over {@code agent_run}, {@code agent_run_intent}, {@code agent_run_tool_event} and
 * {@code agent_run_turn} (section 10, V5). Never cached (section 20). Every user-facing statement filters on
 * {@code user_id} in the SQL (T2, T6).
 *
 * <p>A run is read with its children in one {@code BEGIN IMMEDIATE} transaction, so a reader never sees a row from one
 * update and children from another; an update replaces the row and its children in one transaction. {@code updated_at},
 * the column the orphan rule reads (23.1), is written from the injected clock on every insert, update and turn;
 * {@code scenario} stays NULL, since the harness keeps its runs in memory. Tool durations are stored in whole
 * milliseconds ({@code took_ms}): a sub-millisecond part is dropped.
 */
public final class SqlRunRepository implements RunRepository {

    private static final String RUN_COLUMNS = "id, user_id, model, started_at, ended_at, outcome, reason, model_calls,"
            + " tool_calls, input_tokens, output_tokens";

    private final Database db;
    private final Clock clock;

    public SqlRunRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void insert(UserId owner, RunRecord run) {
        checkOwner(owner, run);
        db.write(c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO agent_run (" + RUN_COLUMNS + ", updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bindRun(s, run);
                s.setString(12, SqlTimes.format(clock.instant()));
                s.executeUpdate();
            } catch (SQLException e) {
                if (SqlErrors.isUniqueViolation(e)) {
                    throw new IllegalArgumentException("run id already exists: " + run.id());
                }
                throw e;
            }
            insertChildren(c, run);
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO agent_run_turn (run_id, seq, user_id, started_at) VALUES (?, 0, ?, ?)")) {
                s.setString(1, run.id().value());
                s.setString(2, owner.value());
                s.setString(3, SqlTimes.format(run.startedAt()));
                s.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<RunRecord> find(UserId owner, RunId id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        return db.write(c -> runs(c, "id = ? AND user_id = ?", id.value(), owner.value()).stream().findFirst());
    }

    @Override
    public boolean update(UserId owner, RunRecord run) {
        checkOwner(owner, run);
        return db.write(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE agent_run SET model = ?, started_at = ?,"
                    + " ended_at = ?, outcome = ?, reason = ?, model_calls = ?, tool_calls = ?, input_tokens = ?,"
                    + " output_tokens = ?, updated_at = ? WHERE id = ? AND user_id = ?")) {
                s.setString(1, run.model());
                s.setString(2, SqlTimes.format(run.startedAt()));
                s.setString(3, run.endedAt().map(SqlTimes::format).orElse(null));
                s.setString(4, run.outcome().orElse(null));
                s.setString(5, run.reason().orElse(null));
                s.setInt(6, run.modelCalls());
                s.setInt(7, run.toolCalls());
                s.setLong(8, run.inputTokens());
                s.setLong(9, run.outputTokens());
                s.setString(10, SqlTimes.format(clock.instant()));
                s.setString(11, run.id().value());
                s.setString(12, owner.value());
                if (s.executeUpdate() == 0) {
                    return false;
                }
            }
            for (String table : List.of("agent_run_intent", "agent_run_tool_event")) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM " + table + " WHERE run_id = ?")) {
                    s.setString(1, run.id().value());
                    s.executeUpdate();
                }
            }
            insertChildren(c, run);
            return true;
        });
    }

    @Override
    public List<RunRecord> forIntent(UserId owner, IntentId intent) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(intent, "intent");
        return db.write(c -> runs(c,
                "user_id = ? AND id IN (SELECT run_id FROM agent_run_intent WHERE intent_id = ?)",
                owner.value(), intent.value()));
    }

    @Override
    public boolean addTurn(UserId owner, RunId id, Instant at) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(at, "at");
        return db.write(c -> {
            // The owner check and the copy of user_id are one statement: a run someone else owns inserts nothing.
            try (PreparedStatement s = c.prepareStatement("INSERT INTO agent_run_turn (run_id, seq, user_id, started_at)"
                    + " SELECT id, (SELECT COALESCE(MAX(seq), -1) + 1 FROM agent_run_turn WHERE run_id = ?), user_id, ?"
                    + " FROM agent_run WHERE id = ? AND user_id = ?")) {
                s.setString(1, id.value());
                s.setString(2, SqlTimes.format(at));
                s.setString(3, id.value());
                s.setString(4, owner.value());
                if (s.executeUpdate() == 0) {
                    return false;
                }
            }
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE agent_run SET updated_at = ? WHERE id = ? AND user_id = ?")) {
                s.setString(1, SqlTimes.format(clock.instant()));
                s.setString(2, id.value());
                s.setString(3, owner.value());
                s.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public int turns(UserId owner, RunId id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        return db.read(c -> count(c, "SELECT COUNT(*) FROM agent_run_turn WHERE run_id = ? AND user_id = ?",
                id.value(), owner.value()));
    }

    @Override
    public int turnsSince(UserId owner, Instant from) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(from, "from");
        return db.read(c -> count(c, "SELECT COUNT(*) FROM agent_run_turn WHERE user_id = ? AND started_at >= ?",
                owner.value(), SqlTimes.format(from)));
    }

    @Override
    public Optional<RunRecord> findForSystem(RunId id) {
        Objects.requireNonNull(id, "id");
        return db.write(c -> runs(c, "id = ?", id.value()).stream().findFirst());
    }

    private static void checkOwner(UserId owner, RunRecord run) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(run, "run");
        if (!run.owner().equals(owner)) {
            throw new IllegalArgumentException("the run's owner is not the caller");
        }
    }

    private static void bindRun(PreparedStatement s, RunRecord run) throws SQLException {
        s.setString(1, run.id().value());
        s.setString(2, run.owner().value());
        s.setString(3, run.model());
        s.setString(4, SqlTimes.format(run.startedAt()));
        s.setString(5, run.endedAt().map(SqlTimes::format).orElse(null));
        s.setString(6, run.outcome().orElse(null));
        s.setString(7, run.reason().orElse(null));
        s.setInt(8, run.modelCalls());
        s.setInt(9, run.toolCalls());
        s.setLong(10, run.inputTokens());
        s.setLong(11, run.outputTokens());
    }

    private static void insertChildren(Connection c, RunRecord run) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO agent_run_intent (run_id, seq, intent_id) VALUES (?, ?, ?)")) {
            for (int i = 0; i < run.intentIds().size(); i++) {
                s.setString(1, run.id().value());
                s.setInt(2, i);
                s.setString(3, run.intentIds().get(i).value());
                s.executeUpdate();
            }
        }
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO agent_run_tool_event (run_id, seq, tool, summary, took_ms) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < run.toolEvents().size(); i++) {
                ToolEvent e = run.toolEvents().get(i);
                s.setString(1, run.id().value());
                s.setInt(2, i);
                s.setString(3, e.tool());
                s.setString(4, e.summary());
                s.setLong(5, e.took().toMillis());
                s.executeUpdate();
            }
        }
    }

    /** The runs matching {@code where}, oldest first (started_at, then id), each with its intents and tool events. */
    private static List<RunRecord> runs(Connection c, String where, String... params) throws SQLException {
        List<RunRecord> out = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(
                "SELECT " + RUN_COLUMNS + " FROM agent_run WHERE " + where + " ORDER BY started_at, id")) {
            for (int i = 0; i < params.length; i++) {
                s.setString(i + 1, params[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String id = r.getString("id");
                    out.add(new RunRecord(
                            new RunId(id),
                            new UserId(r.getString("user_id")),
                            r.getString("model"),
                            SqlTimes.parse(r.getString("started_at")),
                            Optional.ofNullable(r.getString("ended_at")).map(SqlTimes::parse),
                            Optional.ofNullable(r.getString("outcome")),
                            Optional.ofNullable(r.getString("reason")),
                            r.getInt("model_calls"),
                            r.getInt("tool_calls"),
                            r.getLong("input_tokens"),
                            r.getLong("output_tokens"),
                            intents(c, id),
                            toolEvents(c, id)));
                }
            }
        }
        return out;
    }

    private static List<IntentId> intents(Connection c, String runId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT intent_id FROM agent_run_intent WHERE run_id = ? ORDER BY seq")) {
            s.setString(1, runId);
            try (ResultSet r = s.executeQuery()) {
                List<IntentId> out = new ArrayList<>();
                while (r.next()) {
                    out.add(new IntentId(r.getString(1)));
                }
                return out;
            }
        }
    }

    private static List<ToolEvent> toolEvents(Connection c, String runId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT tool, summary, took_ms FROM agent_run_tool_event WHERE run_id = ? ORDER BY seq")) {
            s.setString(1, runId);
            try (ResultSet r = s.executeQuery()) {
                List<ToolEvent> out = new ArrayList<>();
                while (r.next()) {
                    out.add(new ToolEvent(r.getString(1), r.getString(2), Duration.ofMillis(r.getLong(3))));
                }
                return out;
            }
        }
    }

    private static int count(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                s.setString(i + 1, params[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }
}
