package dev.ucplab.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The behaviour every {@link RunRepository} must have (section 10 {@code agent_run}, 23.1, T2, T6): one suite, run
 * against the in-memory adapter now and the SQL adapter in M6.
 */
public abstract class RunRepositoryContract {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00.123456789Z");
    private static final UserId ALICE = new UserId("alice");
    private static final UserId BOB = new UserId("bob");
    private static final IntentId I1 = new IntentId("i1");
    private static final IntentId I2 = new IntentId("i2");

    protected RunRepository repo;

    /** A fresh, empty repository. */
    protected abstract RunRepository create();

    @BeforeEach
    void createRepository() {
        repo = create();
    }

    private static RunRecord ended(String id, UserId owner, List<IntentId> intents) {
        return new RunRecord(new RunId(id), owner, "scripted", T0, Optional.of(T0.plusSeconds(3)),
                Optional.of("waiting_for_approval"), Optional.empty(), 3, 4, 1200, 300, intents,
                List.of(new ToolEvent("search_catalog", "ok", Duration.ofMillis(40)),
                        new ToolEvent("propose_purchase", "ok", Duration.ofMillis(90))));
    }

    // Covers: 23.1, T2, T6 (updatedAt is the owner's own: present for a stored run, empty for another user's or a missing
    // one, and never earlier than the run's insert)
    @Test
    void updatedAt_isOwnerScoped() {
        repo.insert(ALICE, RunRecord.started(new RunId("r1"), ALICE, "scripted", T0));

        assertThat(repo.updatedAt(ALICE, new RunId("r1"))).isPresent();
        assertThat(repo.updatedAt(BOB, new RunId("r1"))).isEmpty();
        assertThat(repo.updatedAt(ALICE, new RunId("missing"))).isEmpty();
    }

    // Covers: section 10 agent_run, T2 (a run reads back as it was written, by its owner)
    @Test
    void insert_thenFindByOwner_roundTrips() {
        RunRecord run = ended("r1", ALICE, List.of(I1));
        repo.insert(ALICE, run);

        assertThat(repo.find(ALICE, new RunId("r1"))).contains(run);
    }

    // Covers: T6, I9 (another user's run reads as empty, exactly like a missing one)
    @Test
    void find_anotherUsersRun_readsAsEmpty() {
        repo.insert(ALICE, RunRecord.started(new RunId("r1"), ALICE, "scripted", T0));

        assertThat(repo.find(BOB, new RunId("r1"))).isEmpty();
        assertThat(repo.find(ALICE, new RunId("missing"))).isEmpty();
    }

    // Covers: T1, T2 (a run is stored only under the owner it names: the caller cannot file a run for someone else)
    @Test
    void insert_ownerMismatch_isRefusedAndNothingStored() {
        RunRecord alices = RunRecord.started(new RunId("r1"), ALICE, "scripted", T0);

        assertThatThrownBy(() -> repo.insert(BOB, alices)).isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.findForSystem(new RunId("r1"))).isEmpty();
    }

    // Covers: section 10 agent_run (run ids are unique)
    @Test
    void insert_duplicateId_isRefused() {
        repo.insert(ALICE, RunRecord.started(new RunId("r1"), ALICE, "scripted", T0));

        assertThatThrownBy(() -> repo.insert(ALICE, RunRecord.started(new RunId("r1"), ALICE, "other", T0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.find(ALICE, new RunId("r1"))).hasValueSatisfying(r -> assertThat(r.model())
                .isEqualTo("scripted"));
    }

    // Covers: 23.1 (a run is updated in place when it ends)
    @Test
    void update_byOwner_replacesTheRun() {
        repo.insert(ALICE, RunRecord.started(new RunId("r1"), ALICE, "scripted", T0));
        RunRecord done = ended("r1", ALICE, List.of(I1));

        assertThat(repo.update(ALICE, done)).isTrue();
        assertThat(repo.find(ALICE, new RunId("r1"))).contains(done);
    }

    // Covers: T6 (an update of a missing run, or of another user's run under a matching record, writes nothing)
    @Test
    void update_missingOrNotOwned_returnsFalseAndWritesNothing() {
        RunRecord alices = RunRecord.started(new RunId("r1"), ALICE, "scripted", T0);
        repo.insert(ALICE, alices);

        assertThat(repo.update(BOB, ended("r1", BOB, List.of()))).isFalse();
        assertThat(repo.update(ALICE, ended("missing", ALICE, List.of()))).isFalse();
        assertThat(repo.find(ALICE, new RunId("r1"))).contains(alices);
        assertThat(repo.findForSystem(new RunId("missing"))).isEmpty();
    }

    // Covers: T1, T2 (an update naming another owner than the caller is refused)
    @Test
    void update_ownerMismatch_isRefused() {
        repo.insert(ALICE, RunRecord.started(new RunId("r1"), ALICE, "scripted", T0));

        assertThatThrownBy(() -> repo.update(BOB, ended("r1", ALICE, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 23.1, T6 (the runs behind an intent are the owner's only, oldest first)
    @Test
    void forIntent_returnsOnlyTheOwnersRunsInInsertOrder() {
        repo.insert(ALICE, ended("r1", ALICE, List.of(I1)));
        repo.insert(BOB, ended("r2", BOB, List.of(I1)));
        repo.insert(ALICE, ended("r3", ALICE, List.of(I2)));
        repo.insert(ALICE, ended("r4", ALICE, List.of(I2, I1)));

        assertThat(repo.forIntent(ALICE, I1)).extracting(RunRecord::id)
                .containsExactly(new RunId("r1"), new RunId("r4"));
        assertThat(repo.forIntent(BOB, I2)).isEmpty();
    }

    // Covers: 23.1, P5 (a run's start is its turn 0: an inserted run has one turn, a missing or unowned run none)
    @Test
    void turns_insertedRunHasItsStartAsTurnZero() {
        repo.insert(ALICE, RunRecord.started(new RunId("r1"), ALICE, "scripted", T0));

        assertThat(repo.turns(ALICE, new RunId("r1"))).isEqualTo(1);
        assertThat(repo.turns(BOB, new RunId("r1"))).as("T6").isZero();
        assertThat(repo.turns(ALICE, new RunId("missing"))).isZero();
    }

    // Covers: 23.1, Decision 3 (each continuation adds one turn, so the cap of 5 continuations is turns <= 6)
    @Test
    void addTurn_byOwner_addsOneTurnEach() {
        repo.insert(ALICE, RunRecord.started(new RunId("r1"), ALICE, "scripted", T0));

        for (int i = 1; i <= 5; i++) {
            assertThat(repo.addTurn(ALICE, new RunId("r1"), T0.plusSeconds(i))).isTrue();
        }

        assertThat(repo.turns(ALICE, new RunId("r1"))).isEqualTo(6);
    }

    // Covers: T1, T2, T6 (a turn cannot be added to another user's run or a missing one: refused, nothing written)
    @Test
    void addTurn_anotherUsersOrMissingRun_isRefused() {
        repo.insert(ALICE, RunRecord.started(new RunId("r1"), ALICE, "scripted", T0));

        assertThat(repo.addTurn(BOB, new RunId("r1"), T0.plusSeconds(1))).isFalse();
        assertThat(repo.addTurn(BOB, new RunId("missing"), T0.plusSeconds(1))).isFalse();

        assertThat(repo.turns(ALICE, new RunId("r1"))).isEqualTo(1);
        assertThat(repo.turnsSince(BOB, T0)).isZero();
    }

    // Covers: 23.1, P5, Decision 3 (the hourly run limit counts every turn of the user's runs since the instant given,
    // inclusive; another user's turns never count)
    @Test
    void turnsSince_countsTheOwnersTurnsFromTheInstantInclusive() {
        repo.insert(ALICE, RunRecord.started(new RunId("r1"), ALICE, "scripted", T0));
        repo.addTurn(ALICE, new RunId("r1"), T0.plusSeconds(10));
        repo.insert(ALICE, RunRecord.started(new RunId("r2"), ALICE, "scripted", T0.plusSeconds(20)));
        repo.insert(BOB, RunRecord.started(new RunId("r3"), BOB, "scripted", T0.plusSeconds(30)));
        repo.addTurn(BOB, new RunId("r3"), T0.plusSeconds(31));

        assertThat(repo.turnsSince(ALICE, T0)).isEqualTo(3);
        assertThat(repo.turnsSince(ALICE, T0.plusNanos(1))).isEqualTo(2);
        assertThat(repo.turnsSince(ALICE, T0.plusSeconds(10))).isEqualTo(2);
        assertThat(repo.turnsSince(ALICE, T0.plusSeconds(21))).isZero();
        assertThat(repo.turnsSince(BOB, T0)).isEqualTo(2);
        assertThat(repo.turnsSince(new UserId("carol"), T0)).isZero();
    }

    // Covers: 23.1 (updating a run when it ends keeps its turns)
    @Test
    void update_keepsTheRunsTurns() {
        repo.insert(ALICE, RunRecord.started(new RunId("r1"), ALICE, "scripted", T0));
        repo.addTurn(ALICE, new RunId("r1"), T0.plusSeconds(1));

        repo.update(ALICE, ended("r1", ALICE, List.of(I1)));

        assertThat(repo.turns(ALICE, new RunId("r1"))).isEqualTo(2);
        assertThat(repo.turnsSince(ALICE, T0)).isEqualTo(2);
    }

    // Covers: T2 (the harness's system read sees a run whoever owns it)
    @Test
    void findForSystem_seesAnyOwnersRun() {
        RunRecord bobs = ended("r2", BOB, List.of());
        repo.insert(BOB, bobs);

        assertThat(repo.findForSystem(new RunId("r2"))).contains(bobs);
    }
}
