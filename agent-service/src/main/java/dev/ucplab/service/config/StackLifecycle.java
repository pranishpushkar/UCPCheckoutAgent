package dev.ucplab.service.config;

import dev.ucplab.core.recovery.RecoveryLoop;
import dev.ucplab.persistence.Database;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the recovery loop, and on shutdown stops the stack in order (m6-plan Stage 2 review, point 6): first the
 * loop, with a grace period for a pass in progress; then the drivers, which may finish their step within the same
 * grace before they are interrupted; and only then the database, so no driver ever writes to a closed pool.
 *
 * <p>Its phase is below the web server's, so it starts before the server accepts a request and stops after the server
 * has stopped taking new ones.
 */
public final class StackLifecycle implements SmartLifecycle {

    static final Duration DEFAULT_GRACE = Duration.ofSeconds(10);
    /** Below Boot's web server lifecycles ({@code DEFAULT_PHASE - 1024} and {@code - 2048}). */
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private static final System.Logger LOG = System.getLogger(StackLifecycle.class.getName());

    private final RecoveryLoop loop;
    private final ExecutorService drivers;
    private final ScheduledExecutorService scheduler;
    private final Database db;
    private final Duration grace;
    private final AtomicBoolean running = new AtomicBoolean();

    StackLifecycle(RecoveryLoop loop, ExecutorService drivers, ScheduledExecutorService scheduler, Database db,
                   Duration grace) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.drivers = Objects.requireNonNull(drivers, "drivers");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.db = Objects.requireNonNull(db, "db");
        this.grace = Objects.requireNonNull(grace, "grace");
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            loop.start();
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (!loop.close(grace)) {
                LOG.log(System.Logger.Level.WARNING, "shutdown: a recovery pass was interrupted");
            }
            scheduler.shutdownNow();
            drivers.shutdown();
            if (!awaitTermination(drivers)) {
                drivers.shutdownNow();
                LOG.log(System.Logger.Level.WARNING, "shutdown: drivers interrupted after the grace period");
                awaitTermination(drivers);
            }
        } finally {
            db.close();
        }
    }

    private boolean awaitTermination(ExecutorService executor) {
        try {
            return executor.awaitTermination(grace.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
