package dev.ucplab.core.recovery;

import dev.ucplab.core.intent.LeaseLostException;
import dev.ucplab.core.intent.StaleVersionException;
import dev.ucplab.core.storage.StorageException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs {@link RecoveryWorker#runOnce} once at startup and then every {@link RecoveryConfig#period()} (7.6). The first
 * pass is scheduled with no delay, so a restarted process picks up its predecessor's intents at once (the leases may
 * still hold them for up to 30 s; the next passes find them). The delay is counted from the end of a pass, so passes
 * never overlap. A pass that throws is logged with a {@link #reason fixed reason}, never the exception's message, and the
 * loop carries on.
 *
 * <p>{@link #close(Duration)} is the graceful stop (m6-plan section 12): no pass starts after it, a pass in progress
 * may finish within the grace period, and one that outlasts it is interrupted. Drivers that a pass handed to a
 * thread-pool executor are not part of the pass: the executor's owner stops them (shutdown, then shutdownNow).
 */
public final class RecoveryLoop implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RecoveryLoop.class.getName());

    private final RecoveryWorker worker;
    private final RecoveryConfig config;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Held by a pass while it runs; close takes it and keeps it, so no pass can start afterwards. */
    private final Semaphore passPermit = new Semaphore(1);
    /** The thread running a pass, so close can interrupt it after the grace period. */
    private final AtomicReference<Thread> passThread = new AtomicReference<>();

    public RecoveryLoop(RecoveryWorker worker, RecoveryConfig config, ScheduledExecutorService scheduler) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Starts the loop. A second call, or a call after close, does nothing. */
    public void start() {
        if (closed.get()) {
            return;
        }
        ScheduledFuture<?> scheduled = scheduler.scheduleWithFixedDelay(
                this::pass, 0, config.period().toMillis(), TimeUnit.MILLISECONDS);
        if (!task.compareAndSet(null, scheduled) || closed.get()) {
            scheduled.cancel(false);
        }
    }

    /** One pass; package-private so a test can run one by hand. Does nothing once the loop is closed. */
    void pass() {
        if (closed.get() || !passPermit.tryAcquire()) {
            return;
        }
        try {
            if (closed.get()) {
                return;
            }
            passThread.set(Thread.currentThread());
            worker.runOnce();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "recovery pass failed: {0}", reason(e));
        } finally {
            passThread.set(null);
            passPermit.release();
        }
    }

    /**
     * The fixed code logged for a failed pass or step (m6-plan section 12): {@code lease_lost}, {@code stale_version},
     * {@code database} for a storage adapter's failure, and {@code internal} for anything else. Never the message, which
     * can carry a request body or a credential.
     */
    static String reason(RuntimeException e) {
        return switch (e) {
            case LeaseLostException l -> "lease_lost";
            case StaleVersionException s -> "stale_version";
            case StorageException s -> "database";
            default -> "internal";
        };
    }

    /**
     * Stops the loop gracefully: no further pass is scheduled or started, a pass in progress may finish for up to
     * {@code grace}, and one still running then is interrupted.
     *
     * @return true when no pass was left running (none was in progress, or it finished within the grace period)
     */
    public boolean close(Duration grace) {
        Objects.requireNonNull(grace, "grace");
        close();
        try {
            if (passPermit.tryAcquire(grace.toNanos(), TimeUnit.NANOSECONDS)) {
                // Kept for good: pass() can never run again.
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Thread running = passThread.get();
        if (running != null) {
            running.interrupt();
        }
        return false;
    }

    /** Stops scheduling further passes, and no pass starts afterwards; a pass already running is not waited for. */
    @Override
    public void close() {
        closed.set(true);
        ScheduledFuture<?> scheduled = task.getAndSet(null);
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }
}
