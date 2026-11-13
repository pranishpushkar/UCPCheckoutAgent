package dev.ucplab.core.agent;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent runs (section 10 {@code agent_run}, 23.1). Every method takes the owner's {@link UserId} (T2), and a run owned
 * by someone else reads as absent (T6, I9). The one exception is {@link #findForSystem}, for the evaluation harness
 * (T2). Both adapters run {@code RunRepositoryContract}.
 *
 * <p><b>Turns</b> (23.1, Decision 3 of m6-plan). A run's start is its turn 0, stored by {@link #insert}; each
 * continuation of the conversation adds one with {@link #addTurn}. The hourly run limit (P5) counts turns, so a
 * continuation counts as a run, and the cap of 5 continuations per run is {@code turns <= 6}. A turn holds a time only,
 * never message text (I8).
 */
public interface RunRepository {

    /**
     * Stores the run and its turn 0 at {@code run.startedAt()}.
     *
     * @throws IllegalArgumentException if {@code run.owner()} is not {@code owner}, or the id already exists
     */
    void insert(UserId owner, RunRecord run);

    /** Empty when the run does not exist or belongs to someone else (T6). */
    Optional<RunRecord> find(UserId owner, RunId id);

    /**
     * Replaces the stored run with {@code run}. False, and nothing written, when no run with that id is owned by
     * {@code owner} (T6).
     *
     * @throws IllegalArgumentException if {@code run.owner()} is not {@code owner}
     */
    boolean update(UserId owner, RunRecord run);

    /** The owner's runs that proposed the intent, oldest first. */
    List<RunRecord> forIntent(UserId owner, IntentId intent);

    /**
     * Adds a turn at {@code at} to the owner's run. False, and nothing written, when no run with that id is owned by
     * {@code owner} (T1, T6).
     */
    boolean addTurn(UserId owner, RunId id, Instant at);

    /** How many turns the owner's run has, turn 0 included; 0 when the run is missing or someone else's (T6). */
    int turns(UserId owner, RunId id);

    /** How many turns of the owner's runs started at or after {@code from} (P5's window, 23.1). */
    int turnsSince(UserId owner, Instant from);

    /**
     * When the owner's run was last written (insert, update or turn), from the repository's clock; empty when the run
     * is missing or someone else's (T6). The orphan rule (23.1) reads it, so no system read is needed.
     */
    Optional<Instant> updatedAt(UserId owner, RunId id);

    /** Evaluation harness only (T2): the run, whoever owns it. */
    Optional<RunRecord> findForSystem(RunId id);
}
