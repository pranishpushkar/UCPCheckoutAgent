package dev.ucplab.eval;

import dev.ucplab.runtime.BudgetLimit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * How an agent row (m5-plan section 9, 13.1 D6) drives its run: the user's message, the script the
 * {@code ScriptedModel} replays, the scripted user's answers, and what the run itself must end as. After the run the
 * approver and the recovery worker act exactly as in M4, on the intents the agent proposed.
 *
 * @param plan the script of each run; A-B picks a different breach by run index
 * @param userAnswers what {@link ScriptedUser} answers to each {@code ask_user}, in order; once they run out it answers
 *     nothing (a timeout)
 * @param runOutcomes the run record outcomes (23.1 statuses, or {@code failed}) the row expects
 * @param minIntents the fewest intents the run may leave
 * @param maxIntents the most intents the run may leave; 0 for A-B
 * @param askFirst the row's claim is S12's: {@code ask_user} is called, and no intent exists for the user when it is
 *     first called
 * @param noSideEffects the row's claim is section 8's: no intent, no approval, only discovery and catalog reads on the
 *     wire, and no new order
 */
public record AgentSpec(String userMessage, Plan plan, List<String> userAnswers, Set<String> runOutcomes,
                        int minIntents, int maxIntents, boolean askFirst, boolean noSideEffects) {

    /** The script of one run. */
    @FunctionalInterface
    public interface Plan {
        Script forRun(int run);
    }

    /**
     * One run's script: a resource under {@code dev/ucplab/eval/scripts/}, and the budget limit the run must breach,
     * if it must. A {@code WALL_CLOCK} breach runs the runtime on a clock that steps forward on every read, so the 30 s
     * pass without waiting for them.
     */
    public record Script(String resource, Optional<BudgetLimit> breach) {
        public Script {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(breach, "breach");
        }

        public static Script of(String resource) {
            return new Script(resource, Optional.empty());
        }
    }

    public AgentSpec {
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(plan, "plan");
        userAnswers = List.copyOf(userAnswers);
        runOutcomes = Set.copyOf(runOutcomes);
        if (runOutcomes.isEmpty()) {
            throw new IllegalArgumentException("an agent row expects at least one run outcome");
        }
        if (minIntents < 0 || maxIntents < minIntents) {
            throw new IllegalArgumentException("intents must be 0 <= min <= max: " + minIntents + ", " + maxIntents);
        }
        if (noSideEffects && maxIntents != 0) {
            throw new IllegalArgumentException("a row with no side effects leaves no intent");
        }
    }

    /** A row with one script for every run. */
    public static AgentSpec of(String userMessage, String resource, List<String> userAnswers, Set<String> runOutcomes,
                               int minIntents, int maxIntents, boolean askFirst) {
        return new AgentSpec(userMessage, run -> Script.of(resource), userAnswers, runOutcomes, minIntents,
                maxIntents, askFirst, false);
    }
}
