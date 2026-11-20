package dev.ucplab.runtime;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.BuyerCountry;
import java.util.Objects;
import java.util.Optional;

/**
 * What a tool knows about its run: the signed-in user (T1, 18.1), the run id, the user's budget for the run (23.1,
 * P9) and the run's buyer country (23.1, 26.7). Never built from model output.
 */
public record ToolContext(AuthContext auth, RunId runId, Optional<Budget> budget, BuyerCountry shipTo) {
    public ToolContext {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(shipTo, "shipTo");
    }

    /** A run for the default buyer country, US. */
    public ToolContext(AuthContext auth, RunId runId, Optional<Budget> budget) {
        this(auth, runId, budget, BuyerCountry.US);
    }

    /** A run with no budget. */
    public ToolContext(AuthContext auth, RunId runId) {
        this(auth, runId, Optional.empty());
    }
}
