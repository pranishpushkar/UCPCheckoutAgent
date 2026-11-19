package dev.ucplab.runtime;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.Budget;
import java.util.Objects;
import java.util.Optional;

/**
 * What a tool knows about its run: the signed-in user (T1, 18.1), the run id, and the user's budget for the run (23.1,
 * P9). Never built from model output.
 */
public record ToolContext(AuthContext auth, RunId runId, Optional<Budget> budget) {
    public ToolContext {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(budget, "budget");
    }

    /** A run with no budget. */
    public ToolContext(AuthContext auth, RunId runId) {
        this(auth, runId, Optional.empty());
    }
}
