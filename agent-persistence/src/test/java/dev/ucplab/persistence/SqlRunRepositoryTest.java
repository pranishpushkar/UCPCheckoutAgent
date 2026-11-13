package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.agent.RunRepository;
import dev.ucplab.core.agent.RunRepositoryContract;
import dev.ucplab.core.agent.ToolEvent;
import dev.ucplab.core.identity.UserId;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shared run contract (section 10 agent_run, 23.1, T2, T6) against SQLite, plus the schema's own guards. */
class SqlRunRepositoryTest extends RunRepositoryContract {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00.123456789Z");
    private static final UserId ALICE = new UserId("alice");

    @TempDir
    Path dir;

    private Database db;

    @Override
    protected RunRepository create() {
        Path file = dir.resolve("runs.db");
        Migrations.migrate(file);
        db = Database.open(file, 2);
        return new SqlRunRepository(db, Clock.fixed(T0.plusSeconds(60), ZoneOffset.UTC));
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }

    private void exec(String sql) {
        db.write(c -> {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
            return null;
        });
    }

    private static final String RAW_RUN = "INSERT INTO agent_run (id, user_id, model, started_at, updated_at, ended_at,"
            + " outcome, reason, model_calls) VALUES ('%s', 'alice', 'scripted/v1', 't', 't', %s, %s, %s, %d)";

    private static String raw(String id, String endedAt, String outcome, String reason, int modelCalls) {
        return String.format(RAW_RUN, id, endedAt, outcome, reason, modelCalls);
    }

    // Covers: section 10, 23.1 (the outcome CHECK refuses a status outside the 23.1 list; nothing is stored)
    @Test
    void insert_unknownOutcome_isRefusedByTheDatabase() {
        RunRecord bad = new RunRecord(new RunId("r-bad"), ALICE, "scripted/v1", T0, Optional.of(T0.plusSeconds(1)),
                Optional.of("exploded"), Optional.empty(), 1, 0, 0, 0, List.of(), List.of());

        assertThatThrownBy(() -> repo.insert(ALICE, bad)).isInstanceOf(DatabaseException.class);
        assertThat(repo.find(ALICE, new RunId("r-bad"))).isEmpty();
        assertThat(repo.turns(ALICE, new RunId("r-bad"))).as("turn 0 rolled back with the run").isZero();
    }

    // Covers: section 10 (each 23.1 outcome is accepted by the CHECK)
    @Test
    void insert_everyListedOutcome_isAccepted() {
        int n = 0;
        for (String outcome : List.of("done", "waiting_for_approval", "budget_exceeded", "user_timeout", "failed")) {
            RunRecord run = new RunRecord(new RunId("r-" + n++), ALICE, "scripted/v1", T0,
                    Optional.of(T0.plusSeconds(1)), Optional.of(outcome), Optional.empty(), 0, 0, 0, 0, List.of(),
                    List.of());
            repo.insert(ALICE, run);
            assertThat(repo.find(ALICE, run.id())).contains(run);
        }
    }

    // Covers: section 10 (the schema refuses an ended run without an outcome, an outcome without an end, and a reason
    // on a run in progress, even when written around the repository)
    @Test
    void schema_refusesInconsistentEndOutcomeAndReason() {
        assertThatThrownBy(() -> exec(raw("x1", "'t'", "NULL", "NULL", 0))).isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec(raw("x2", "NULL", "'done'", "NULL", 0))).isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec(raw("x3", "NULL", "NULL", "'model_error'", 0)))
                .isInstanceOf(DatabaseException.class);
        exec(raw("ok", "'t'", "'failed'", "'model_error'", 0));
        exec(raw("ok2", "NULL", "NULL", "NULL", 0));
    }

    // Covers: section 10 (counts, tool durations and sequence numbers are never negative)
    @Test
    void schema_refusesNegativeCounts() {
        assertThatThrownBy(() -> exec(raw("n1", "NULL", "NULL", "NULL", -1))).isInstanceOf(DatabaseException.class);
        for (String column : List.of("tool_calls", "input_tokens", "output_tokens")) {
            assertThatThrownBy(() -> exec("INSERT INTO agent_run (id, user_id, model, started_at, updated_at, " + column
                    + ") VALUES ('n-" + column + "', 'alice', 'm', 't', 't', -1)")).isInstanceOf(DatabaseException.class);
        }
        exec(raw("parent", "NULL", "NULL", "NULL", 0));
        assertThatThrownBy(() -> exec("INSERT INTO agent_run_tool_event (run_id, seq, tool, summary, took_ms)"
                + " VALUES ('parent', 0, 'search_catalog', 'ok', -1)")).isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec("INSERT INTO agent_run_turn (run_id, seq, user_id, started_at)"
                + " VALUES ('parent', -1, 'alice', 't')")).isInstanceOf(DatabaseException.class);
    }

    // Covers: section 10 (a child row needs its run)
    @Test
    void schema_refusesChildrenOfAMissingRun() {
        assertThatThrownBy(() -> exec("INSERT INTO agent_run_intent (run_id, seq, intent_id) VALUES ('ghost', 0, 'i')"))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> exec("INSERT INTO agent_run_turn (run_id, seq, user_id, started_at)"
                + " VALUES ('ghost', 0, 'alice', 't')")).isInstanceOf(DatabaseException.class);
    }

    // Covers: 23.1 (tool durations are stored in whole milliseconds; a sub-millisecond part is dropped, never rounded up)
    @Test
    void toolEventDuration_isStoredInWholeMilliseconds() {
        RunRecord run = new RunRecord(new RunId("r-ms"), ALICE, "scripted/v1", T0, Optional.empty(), Optional.empty(),
                Optional.empty(), 1, 1, 0, 0, List.of(),
                List.of(new ToolEvent("search_catalog", "ok", Duration.ofNanos(40_999_999))));
        repo.insert(ALICE, run);

        assertThat(repo.find(ALICE, run.id()).orElseThrow().toolEvents())
                .containsExactly(new ToolEvent("search_catalog", "ok", Duration.ofMillis(40)));
    }

    // Covers: section 10, 23.1 (updated_at, the orphan rule's column, is written from the injected clock on insert,
    // update and each turn)
    @Test
    void updatedAt_comesFromTheInjectedClock() {
        repo.insert(ALICE, RunRecord.started(new RunId("r-u"), ALICE, "scripted/v1", T0));

        String stored = db.read(c -> {
            try (Statement s = c.createStatement();
                 var r = s.executeQuery("SELECT updated_at, scenario FROM agent_run WHERE id = 'r-u'")) {
                r.next();
                return r.getString(1) + "|" + r.getString(2);
            }
        });
        assertThat(stored).isEqualTo(SqlTimes.format(T0.plusSeconds(60)) + "|null");
    }
}
