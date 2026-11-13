package dev.ucplab.runtime;

import dev.ucplab.core.agent.Usage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What one run has used of its {@link AgentBudget} (6.3 [CONTRACT]). Every limit is checked before each model call and
 * each tool call, and refuses the call once nothing of it is left: with the defaults, the seventh model call, the ninth
 * tool call, any call at or after 30 s, and any call once 20,000 tokens are used. Tokens are known only after a turn, so {@link #afterTurn} may take the run past the ceiling once; the
 * next call is then refused. The wall clock is the time since the meter was made, less the time spent waiting for the
 * user in {@link #waitingForUser}, both read from the injected {@link Clock}. One meter per run, used by the run's own
 * thread only.
 */
public final class BudgetMeter {

    private final AgentBudget budget;
    private final Clock clock;
    private final Instant start;
    private Duration userWait = Duration.ZERO;
    private boolean waiting;
    private int modelCalls;
    private int toolCalls;
    private Usage usage = Usage.NONE;

    public BudgetMeter(AgentBudget budget, Clock clock) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.start = clock.instant();
    }

    /** Counts a model call, or names the limit that refuses it (the call is then not counted and must not be made). */
    public Optional<BudgetLimit> beforeModelCall() {
        Optional<BudgetLimit> refused = modelCalls >= budget.maxModelCalls()
                ? Optional.of(BudgetLimit.MODEL_CALLS) : shared();
        if (refused.isEmpty()) {
            modelCalls++;
        }
        return refused;
    }

    /** Counts a tool call, or names the limit that refuses it (the call is then not counted and must not be made). */
    public Optional<BudgetLimit> beforeToolCall() {
        Optional<BudgetLimit> refused = toolCalls >= budget.maxToolCalls()
                ? Optional.of(BudgetLimit.TOOL_CALLS) : shared();
        if (refused.isEmpty()) {
            toolCalls++;
        }
        return refused;
    }

    /** Adds what a model turn used. It may take the run past the token ceiling; the next call is refused. */
    public void afterTurn(Usage turn) {
        usage = usage.plus(Objects.requireNonNull(turn, "turn"));
    }

    /** Runs {@code wait}, a wait for the user's answer, and leaves its duration out of the wall clock. Not nested. */
    public <T> T waitingForUser(Supplier<T> wait) {
        Objects.requireNonNull(wait, "wait");
        if (waiting) {
            throw new IllegalStateException("already waiting for the user");
        }
        waiting = true;
        Instant from = clock.instant();
        try {
            return wait.get();
        } finally {
            Duration waited = Duration.between(from, clock.instant());
            userWait = userWait.plus(waited.isNegative() ? Duration.ZERO : waited);
            waiting = false;
        }
    }

    /** The run's wall-clock time so far, human waiting time excluded. */
    public Duration elapsed() {
        Duration elapsed = Duration.between(start, clock.instant()).minus(userWait);
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }

    /** The wall-clock time left, never negative: the per-call timeout for a model call (6.3). */
    public Duration remaining() {
        Duration left = budget.wallClock().minus(elapsed());
        return left.isNegative() ? Duration.ZERO : left;
    }

    public int modelCalls() {
        return modelCalls;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public Usage usage() {
        return usage;
    }

    /** The limits every call is checked against: tokens, then the wall clock. Refused when nothing of one is left. */
    private Optional<BudgetLimit> shared() {
        if (usage.total() >= budget.maxTokens()) {
            return Optional.of(BudgetLimit.TOKENS);
        }
        if (elapsed().compareTo(budget.wallClock()) >= 0) {
            return Optional.of(BudgetLimit.WALL_CLOCK);
        }
        return Optional.empty();
    }
}
