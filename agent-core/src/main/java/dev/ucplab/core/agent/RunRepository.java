package dev.ucplab.core.agent;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import java.util.List;
import java.util.Optional;

/**
 * Agent runs (section 10 {@code agent_run}, 23.1). Every method takes the owner's {@link UserId} (T2), and a run owned
 * by someone else reads as absent (T6, I9). The one exception is {@link #findForSystem}, for the evaluation harness
 * (T2). In memory until M6, which adds the SQL adapter with the REST API; both run {@code RunRepositoryContract}.
 */
public interface RunRepository {

    /** @throws IllegalArgumentException if {@code run.owner()} is not {@code owner}, or the id already exists */
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

    /** Evaluation harness only (T2): the run, whoever owns it. */
    Optional<RunRecord> findForSystem(RunId id);
}
