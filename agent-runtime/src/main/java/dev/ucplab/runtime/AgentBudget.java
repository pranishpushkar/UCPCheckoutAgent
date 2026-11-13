package dev.ucplab.runtime;

import java.time.Duration;
import java.util.Objects;

/**
 * The limits of one agent run (6.3 [CONTRACT]): model calls, tool calls, wall clock (human waiting time excluded) and
 * the token ceiling (input plus output tokens). A call is refused once nothing of a limit is left, and the run ends as
 * {@code BUDGET_EXCEEDED}; only the token count can go past its limit, by one turn. {@link BudgetMeter} enforces them.
 */
public record AgentBudget(int maxModelCalls, int maxToolCalls, Duration wallClock, long maxTokens) {

    /** 6 model calls, 8 tool calls, 30 s and 20,000 tokens (6.3, D5). */
    public static final AgentBudget DEFAULT = new AgentBudget(6, 8, Duration.ofSeconds(30), 20_000);

    public AgentBudget {
        Objects.requireNonNull(wallClock, "wallClock");
        if (maxModelCalls < 1 || maxToolCalls < 1 || maxTokens < 1) {
            throw new IllegalArgumentException("budget limits must be positive");
        }
        if (wallClock.isNegative() || wallClock.isZero()) {
            throw new IllegalArgumentException("wallClock must be positive");
        }
    }
}
