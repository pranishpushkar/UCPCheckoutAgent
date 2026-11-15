package dev.ucplab.core.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Sleeper} is a one-method functional interface, not a port with implementations to keep in step (H3 P4, the
 * parity table): the production binding is {@code Thread::sleep} and tests pass a lambda. One smoke test each way.
 */
class SleeperTest {

    // Covers: 7.6, 7.8 (a lambda is a Sleeper: it is handed the duration, and nothing really waits)
    @Test
    void aLambda_receivesTheDuration() throws InterruptedException {
        List<Duration> waited = new ArrayList<>();
        Sleeper sleeper = waited::add;

        sleeper.sleep(Duration.ofSeconds(5));

        assertThat(waited).containsExactly(Duration.ofSeconds(5));
    }

    // Covers: 7.6 (the production binding, Thread::sleep, waits and honours an interrupt)
    @Test
    void theProductionBinding_sleeps_andIsInterruptible() throws InterruptedException {
        Sleeper sleeper = Thread::sleep;

        sleeper.sleep(Duration.ofMillis(1));
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> sleeper.sleep(Duration.ofMillis(1))).isInstanceOf(InterruptedException.class);
        assertThat(Thread.interrupted()).as("the flag is cleared by the exception").isFalse();
    }
}
