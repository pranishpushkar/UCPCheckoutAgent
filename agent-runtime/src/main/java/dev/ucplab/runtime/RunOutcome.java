package dev.ucplab.runtime;

import dev.ucplab.core.intent.IntentId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
     * The model call or a tool failed. {@code reason} is ours: {@code model_error} or {@code tool_error} (and
     * {@code script_drift} in the run record of a drifted scripted run), never an exception message. A
     * {@code model_error} has its {@code cause} (6.5), from a closed vocabulary; a failed model call is tried again only
     * as 6.3 says.
     */
    record Failed(String reason, Optional<String> cause) implements RunOutcome {
        public Failed {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(cause, "cause");
        }

        /** A failure with no cause: a tool's, or an internal one. */
        public Failed(String reason) {
            this(reason, Optional.empty());
        }
    }
}
