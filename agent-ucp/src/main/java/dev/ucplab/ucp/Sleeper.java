package dev.ucplab.ucp;

import java.time.Duration;

/** Waits between retries (8.2). Injected so tests do not sleep. Never call it while holding a lock (7.8). */
@FunctionalInterface
public interface Sleeper {

    /** Sleeps on the calling thread. */
    Sleeper THREAD = Thread::sleep;

    void sleep(Duration duration) throws InterruptedException;
}
