package dev.ucplab.core.recovery;

import java.time.Duration;

/**
 * The only place the recovery worker waits (7.5 "Who waits"): {@code advance} never sleeps, so the worker sleeps until
 * a step's {@code nextAttemptAt}. Injected so tests move a clock instead of waiting.
 */
@FunctionalInterface
public interface Sleeper {

    /** Waits for {@code duration}; the production binding is {@code Thread.sleep}. */
    void sleep(Duration duration) throws InterruptedException;
}
