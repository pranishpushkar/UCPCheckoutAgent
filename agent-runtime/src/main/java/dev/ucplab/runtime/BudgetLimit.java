package dev.ucplab.runtime;

/** Which {@link AgentBudget} limit refused a call (6.3). */
public enum BudgetLimit {
    MODEL_CALLS,
    TOOL_CALLS,
    WALL_CLOCK,
    TOKENS
}
