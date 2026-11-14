package dev.ucplab.service.run;

import java.time.Duration;
import java.util.Objects;

/**
 * The supervisor's limits (m6-plan sections 3.2 and 3.3, 23.1). {@code runResponseWait} is how long a request waits for
 * a settle point (60 s); {@code askTimeout} is the runtime's {@code ask_user} wait (5 min); a stored run without an
 * outcome and with no live run here is an orphan after {@code askTimeout + runResponseWait} (6 min); a conversation is
 * dropped after {@code idle} (30 min) and at most {@code maxLive} are held (100); a user may start
 * {@code maxPerHour} turns in an hour (P5, 10) and a run has at most {@code maxTurns} turns (a start and 5
 * continuations, 6).
 */
public record RunSupervisorConfig(Duration runResponseWait, Duration askTimeout, Duration idle, int maxLive,
                                  int maxPerHour, int maxTurns) {

    public RunSupervisorConfig {
        Objects.requireNonNull(runResponseWait, "runResponseWait");
        Objects.requireNonNull(askTimeout, "askTimeout");
        Objects.requireNonNull(idle, "idle");
        if (runResponseWait.isNegative() || runResponseWait.isZero() || maxLive < 1 || maxPerHour < 1
                || maxTurns < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }

    public static RunSupervisorConfig defaults() {
        return new RunSupervisorConfig(Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofMinutes(30), 100, 10,
                6);
    }

    /** How old an in-progress record with no live run must be before it ends as interrupted. */
    public Duration orphanAfter() {
        return askTimeout.plus(runResponseWait);
    }
}
