package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Sleeper} is a one-method functional interface, not a port with implementations to keep in step (H3 P4, the
 * parity table): the production binding is {@link Sleeper#THREAD} and tests pass a lambda. One smoke test each way.
 */
class SleeperTest {

    // Covers: 8.2 (a lambda is a Sleeper: it is handed the backoff, and nothing really waits)
    @Test
    void aLambda_receivesTheDuration() throws InterruptedException {
        List<Duration> waited = new ArrayList<>();
        Sleeper sleeper = waited::add;

        sleeper.sleep(Duration.ofMillis(200));

        assertThat(waited).containsExactly(Duration.ofMillis(200));
    }

    // Covers: 8.2 (THREAD sleeps and honours an interrupt, so a retry loop can be stopped)
    @Test
    void thread_sleeps_andIsInterruptible() throws InterruptedException {
        Sleeper.THREAD.sleep(Duration.ofMillis(1));
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> Sleeper.THREAD.sleep(Duration.ofMillis(1))).isInstanceOf(InterruptedException.class);
        assertThat(Thread.interrupted()).isFalse();
    }
}
