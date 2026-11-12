package dev.ucplab.core.recovery;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs {@link RecoveryWorker#runOnce} once at startup and then every {@link RecoveryConfig#period()} (7.6). The first
 * pass is scheduled with no delay, so a restarted process picks up its predecessor's intents at once (the leases may
 * still hold them for up to 30 s; the next passes find them). The delay is counted from the end of a pass, so passes
 * never overlap. A pass that throws is logged and the loop carries on.
 */
public final class RecoveryLoop implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RecoveryLoop.class.getName());

    private final RecoveryWorker worker;
    private final RecoveryConfig config;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();

    public RecoveryLoop(RecoveryWorker worker, RecoveryConfig config, ScheduledExecutorService scheduler) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Starts the loop. A second call does nothing. */
    public void start() {
        ScheduledFuture<?> scheduled = scheduler.scheduleWithFixedDelay(
                this::pass, 0, config.period().toMillis(), TimeUnit.MILLISECONDS);
        if (!task.compareAndSet(null, scheduled)) {
            scheduled.cancel(false);
        }
    }

    /** One pass; public so a caller can run the startup pass itself. */
    void pass() {
        try {
            worker.runOnce();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "recovery pass failed: {0}", e.getClass().getName());
        }
    }

    /** Stops scheduling further passes; a pass already running finishes. */
    @Override
    public void close() {
        ScheduledFuture<?> scheduled = task.getAndSet(null);
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }
}
