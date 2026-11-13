package dev.ucplab.runtime;

import dev.ucplab.core.intent.IntentId;
import java.util.List;
import java.util.Objects;

/** How an agent run ended (m5-plan section 4; the 23.1 statuses). */
public sealed interface RunOutcome {

    /** The model answered without a tool call. {@code intents} are those this run proposed. */
    record Done(String text, List<IntentId> intents) implements RunOutcome {
        public Done {
            Objects.requireNonNull(text, "text");
            intents = List.copyOf(Objects.requireNonNull(intents, "intents"));
        }
    }

    /** A limit refused the next model or tool call (6.3). An intent already proposed stays as it is. */
    record BudgetExceeded(BudgetLimit which, List<IntentId> intents) implements RunOutcome {
        public BudgetExceeded {
            Objects.requireNonNull(which, "which");
            intents = List.copyOf(Objects.requireNonNull(intents, "intents"));
        }
    }

    /** {@code ask_user} had no answer in time; no further tool call was made (6.2). */
    record UserTimedOut(String question) implements RunOutcome {
        public UserTimedOut {
            Objects.requireNonNull(question, "question");
        }
    }

    /**
     * The model call or a tool failed. Not retried. {@code reason} is ours: {@code model_error} or {@code tool_error}
     * (and {@code script_drift} in the run record of a drifted scripted run), never an exception message.
     */
    record Failed(String reason) implements RunOutcome {
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
