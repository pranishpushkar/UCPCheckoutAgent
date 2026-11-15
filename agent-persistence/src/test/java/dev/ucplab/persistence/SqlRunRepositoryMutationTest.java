package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.agent.ToolEvent;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** H3 P9: every column of an agent run, written, replaced and touched (section 10 agent_run, 23.1), per mutant. */
class SqlRunRepositoryMutationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final UserId ALICE = new UserId("alice");

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(T0);
    private Database db;
    private SqlRunRepository runs;

    @BeforeEach
    void open() {
        Path file = dir.resolve("runs.db");
        Migrations.migrate(file);
        db = Database.open(file, 2);
        runs = new SqlRunRepository(db, clock);
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static RunRecord run(String model, Instant started, Instant ended, String outcome, String reason, int modelCalls,
            int toolCalls, long in, long out, List<IntentId> intents, List<ToolEvent> events) {
        return new RunRecord(new RunId("r1"), ALICE, model, started, Optional.ofNullable(ended), Optional.ofNullable(outcome),
                Optional.ofNullable(reason), modelCalls, toolCalls, in, out, intents, events);
    }

    // Covers: 23.1 (a run that ended with a reason keeps it, with every other column, and an update replaces every column and every
    // child: the intents and the tool events it had are gone). Kills SqlRunRepository.bindRun:204 and update:90, :104 VoidMethodCall
    @Test
    void aRunIsStoredAndReplacedColumnByColumn() {
        RunRecord first = run("model-a", T0, T0.plusSeconds(3), "failed", "model_error", 1, 2, 30, 40,
                List.of(new IntentId("i1"), new IntentId("i2")),
                List.of(new ToolEvent("search_catalog", "ok", Duration.ofMillis(40)), new ToolEvent("propose_purchase", "not_found", Duration.ofMillis(90))));
        runs.insert(ALICE, first);
        assertThat(runs.find(ALICE, new RunId("r1"))).contains(first);

        RunRecord second = run("model-b", T0.plusSeconds(1), T0.plusSeconds(9), "budget_exceeded", "script_drift", 7, 8, 900, 1000,
                List.of(new IntentId("i3")), List.of(new ToolEvent("lookup", "ok", Duration.ofMillis(5))));
        assertThat(runs.update(ALICE, second)).isTrue();

        assertThat(runs.find(ALICE, new RunId("r1"))).contains(second);
        assertThat(runs.forIntent(ALICE, new IntentId("i1"))).isEmpty();
        assertThat(runs.forIntent(ALICE, new IntentId("i3"))).extracting(RunRecord::id).containsExactly(new RunId("r1"));
    }

    // Covers: 23.1 (a turn, and a replaced run, move the run's updated-at to now: it is what tells a run is alive). Kills
    // SqlRunRepository.addTurn:143, :144 VoidMethodCall (the owner and id of the touch)
    @Test
    void aTurnAndAnUpdate_moveTheUpdatedAtToNow() {
        runs.insert(ALICE, RunRecord.started(new RunId("r1"), ALICE, "model-a", T0));
        assertThat(runs.updatedAt(ALICE, new RunId("r1"))).contains(T0);

        clock.advance(Duration.ofSeconds(7));
        assertThat(runs.addTurn(ALICE, new RunId("r1"), clock.instant())).isTrue();
        assertThat(runs.updatedAt(ALICE, new RunId("r1"))).contains(T0.plusSeconds(7));

        clock.advance(Duration.ofSeconds(5));
        runs.update(ALICE, run("model-a", T0, T0.plusSeconds(1), "done", null, 0, 0, 0, 0, List.of(), List.of()));
        assertThat(runs.updatedAt(ALICE, new RunId("r1"))).contains(T0.plusSeconds(12));
    }
}
