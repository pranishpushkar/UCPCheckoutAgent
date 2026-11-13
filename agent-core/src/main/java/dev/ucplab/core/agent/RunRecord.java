package dev.ucplab.core.agent;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An agent run as it is stored: the columns of section 10 {@code agent_run}, plus the intents the run proposed and its
 * tool events. {@code owner} comes from the run's {@code AuthContext}, never from the model (T1). {@code outcome} is
 * empty while the run is in progress, then one of the 23.1 statuses ({@code done}, {@code waiting_for_approval},
 * {@code budget_exceeded}, {@code user_timeout}) or {@code failed}. It holds no prompt, model text or tool output.
 */
public record RunRecord(
        RunId id,
        UserId owner,
        String model,
        Instant startedAt,
        Optional<Instant> endedAt,
        Optional<String> outcome,
        int modelCalls,
        int toolCalls,
        long inputTokens,
        long outputTokens,
        List<IntentId> intentIds,
        List<ToolEvent> toolEvents) {

    public RunRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        Objects.requireNonNull(outcome, "outcome");
        intentIds = List.copyOf(Objects.requireNonNull(intentIds, "intentIds"));
        toolEvents = List.copyOf(Objects.requireNonNull(toolEvents, "toolEvents"));
        if (modelCalls < 0 || toolCalls < 0 || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        if (endedAt.isPresent() && endedAt.get().isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt is before startedAt");
        }
        if (endedAt.isPresent() != outcome.isPresent()) {
            throw new IllegalArgumentException("a run has an outcome exactly when it has ended");
        }
    }

    /** A run that has just started: no calls, no intents, no outcome. */
    public static RunRecord started(RunId id, UserId owner, String model, Instant at) {
        return new RunRecord(id, owner, model, at, Optional.empty(), Optional.empty(), 0, 0, 0, 0, List.of(), List.of());
    }
}
