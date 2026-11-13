package dev.ucplab.eval;

import dev.ucplab.runtime.UserChannel;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.function.IntSupplier;

/**
 * The user of an agent row (m5-plan section 9): answers each {@code ask_user} with the row's next answer. When the
 * first question arrives it counts the intents the run's user has in the repository, which is S12's claim (no intent
 * before the first {@code ask_user}).
 *
 * <p>It honours the timeout: an answer comes back at once, well inside it, and when the answers have run out it
 * answers nothing, which the runtime treats as the timeout ({@code user_timeout}), without sleeping the 5 minutes.
 * It keeps no question and no answer for the evidence, only counts. One run, one thread.
 */
final class ScriptedUser implements UserChannel {

    private final Queue<String> answers;
    private final IntSupplier intentCount;
    private int asks;
    private Optional<Integer> intentsAtFirstAsk = Optional.empty();

    /** @param intentCount how many intents the run's user has now, read from the run's repository */
    ScriptedUser(List<String> answers, IntSupplier intentCount) {
        this.answers = new ArrayDeque<>(answers);
        this.intentCount = Objects.requireNonNull(intentCount, "intentCount");
    }

    @Override
    public Optional<String> ask(String question, Duration timeout) {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(timeout, "timeout");
        if (asks == 0) {
            intentsAtFirstAsk = Optional.of(intentCount.getAsInt());
        }
        asks++;
        if (timeout.isNegative() || timeout.isZero()) {
            return Optional.empty();
        }
        return Optional.ofNullable(answers.poll());
    }

    int asks() {
        return asks;
    }

    /** The user's intents when the first question arrived; empty if the agent never asked. */
    Optional<Integer> intentsAtFirstAsk() {
        return intentsAtFirstAsk;
    }
}
