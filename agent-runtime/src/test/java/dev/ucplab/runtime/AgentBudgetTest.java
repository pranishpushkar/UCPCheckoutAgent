package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.agent.Usage;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AgentBudgetTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);

    private BudgetMeter meter() {
        return new BudgetMeter(AgentBudget.DEFAULT, clock);
    }

    /** A stand-in for {@code UserChannel.ask} (C9): the user takes {@code took} to answer. */
    private Supplier<Optional<String>> userAnswersAfter(Duration took) {
        return () -> {
            clock.advance(took);
            return Optional.of("the blue one");
        };
    }

    // Covers: 6.3 (the defaults: 6 model calls, 8 tool calls, 30 s, 20,000 tokens)
    @Test
    void defaults_areTheContractValues() {
        assertThat(AgentBudget.DEFAULT).isEqualTo(new AgentBudget(6, 8, Duration.ofSeconds(30), 20_000));
    }

    // Covers: 6.3 (every limit is positive and the wall clock is set)
    @Test
    void budget_invalidLimits_areRefused() {
        assertThatThrownBy(() -> new AgentBudget(0, 8, Duration.ofSeconds(30), 20_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentBudget(6, 0, Duration.ofSeconds(30), 20_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentBudget(6, 8, Duration.ZERO, 20_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentBudget(6, 8, Duration.ofSeconds(-1), 20_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentBudget(6, 8, Duration.ofSeconds(30), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentBudget(6, 8, null, 20_000)).isInstanceOf(NullPointerException.class);
    }

    // Covers: 6.3 (the sixth model call is made; the seventh is refused and not counted)
    @Test
    void modelCalls_atTheLimitAllowed_onePastRefused() {
        BudgetMeter meter = meter();
        for (int i = 1; i <= 6; i++) {
            assertThat(meter.beforeModelCall()).as("call " + i).isEmpty();
        }

        assertThat(meter.beforeModelCall()).contains(BudgetLimit.MODEL_CALLS);
        assertThat(meter.modelCalls()).isEqualTo(6);
    }

    // Covers: 6.3 (the eighth tool call is made; the ninth is refused and not counted)
    @Test
    void toolCalls_atTheLimitAllowed_onePastRefused() {
        BudgetMeter meter = meter();
        for (int i = 1; i <= 8; i++) {
            assertThat(meter.beforeToolCall()).as("call " + i).isEmpty();
        }

        assertThat(meter.beforeToolCall()).contains(BudgetLimit.TOOL_CALLS);
        assertThat(meter.toolCalls()).isEqualTo(8);
        assertThat(meter.beforeModelCall()).as("model calls are counted apart").isEmpty();
    }

    // Covers: 6.3 (a call just before 30 s is made; at 30 s nothing is left and the call is refused, as one past)
    @Test
    void wallClock_justBeforeTheLimitAllowed_atAndPastRefused() {
        BudgetMeter meter = meter();
        clock.advance(Duration.ofSeconds(30).minusMillis(1));
        assertThat(meter.beforeModelCall()).isEmpty();
        assertThat(meter.remaining()).isEqualTo(Duration.ofMillis(1));

        clock.advance(Duration.ofMillis(1));
        assertThat(meter.beforeModelCall()).contains(BudgetLimit.WALL_CLOCK);
        assertThat(meter.beforeToolCall()).contains(BudgetLimit.WALL_CLOCK);

        clock.advance(Duration.ofMillis(1));
        assertThat(meter.beforeToolCall()).contains(BudgetLimit.WALL_CLOCK);
        assertThat(meter.remaining()).isZero();
        assertThat(meter.modelCalls()).isEqualTo(1);
    }

    // Covers: 6.3 (with 19,999 tokens used a call is made; at 20,000 nothing is left and the next call is refused)
    @Test
    void tokens_oneBelowTheCeilingAllowed_atAndPastRefused() {
        BudgetMeter meter = meter();
        meter.afterTurn(new Usage(19_000, 999));
        assertThat(meter.beforeModelCall()).isEmpty();

        meter.afterTurn(new Usage(1, 0));
        assertThat(meter.usage().total()).isEqualTo(20_000);
        assertThat(meter.beforeModelCall()).contains(BudgetLimit.TOKENS);
        assertThat(meter.beforeToolCall()).contains(BudgetLimit.TOKENS);

        meter.afterTurn(new Usage(1, 0));
        assertThat(meter.beforeModelCall()).contains(BudgetLimit.TOKENS);
    }

    // Covers: 6.3 (the token count is known only after a turn: one turn may overshoot, and the next call is refused)
    @Test
    void tokens_oneTurnOvershoots_thenTheNextCallIsRefused() {
        BudgetMeter meter = meter();
        meter.afterTurn(new Usage(15_000, 1_000));
        assertThat(meter.beforeModelCall()).as("4,000 tokens left").isEmpty();

        meter.afterTurn(new Usage(8_000, 2_000));
        assertThat(meter.usage().total()).as("the turn went past the ceiling").isEqualTo(26_000);

        assertThat(meter.beforeModelCall()).contains(BudgetLimit.TOKENS);
        assertThat(meter.modelCalls()).isEqualTo(1);
    }

    // Covers: 6.3 (human waiting time is excluded: ten minutes waiting for the user uses none of the 30 s)
    @Test
    void wallClock_userWaitOfTenMinutes_isExcluded() {
        BudgetMeter meter = meter();
        clock.advance(Duration.ofSeconds(10));

        Optional<String> answer = meter.waitingForUser(userAnswersAfter(Duration.ofMinutes(10)));

        assertThat(answer).contains("the blue one");
        assertThat(meter.elapsed()).isEqualTo(Duration.ofSeconds(10));
        assertThat(meter.beforeModelCall()).isEmpty();
        clock.advance(Duration.ofSeconds(20));
        assertThat(meter.beforeToolCall()).as("10 s before and 20 s after the wait").contains(BudgetLimit.WALL_CLOCK);
    }

    // Covers: 6.3 (a wait that ends with an exception, such as an interrupted ask, is still left out)
    @Test
    void wallClock_userWaitThatThrows_isStillExcluded() {
        BudgetMeter meter = meter();

        assertThatThrownBy(() -> meter.waitingForUser(() -> {
            clock.advance(Duration.ofMinutes(5));
            throw new IllegalStateException("interrupted");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(meter.elapsed()).isZero();
        assertThat(meter.beforeModelCall()).isEmpty();
    }

    // Covers: 6.3 (waits do not nest: the run has one question open at a time)
    @Test
    void waitingForUser_nested_isRefused() {
        BudgetMeter meter = meter();

        assertThatThrownBy(() -> meter.waitingForUser(() -> meter.waitingForUser(() -> "inner")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(meter.waitingForUser(() -> "again")).as("the meter is usable afterwards").isEqualTo("again");
    }
}
