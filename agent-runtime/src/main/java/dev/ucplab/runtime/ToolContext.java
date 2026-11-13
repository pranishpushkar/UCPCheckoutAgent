package dev.ucplab.runtime;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.identity.AuthContext;
import java.util.Objects;

/** What a tool knows about its run: the signed-in user (T1, 18.1) and the run id. Never built from model output. */
public record ToolContext(AuthContext auth, RunId runId) {
    public ToolContext {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(runId, "runId");
    }
}
