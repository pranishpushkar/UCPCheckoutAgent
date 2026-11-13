package dev.ucplab.core.recovery;

import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.LeaseLostException;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.RecoveryScan;
import dev.ucplab.core.intent.StaleVersionException;
import dev.ucplab.core.intent.StepResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Recovery of intents a crash or a lapsed lease left behind (7.6). One {@link #runOnce pass} claims the intents that
 * need a worker, oldest first, up to a limit, and drives each as a series of short {@code advance} calls.
 *
 * <p><b>What a pass picks up.</b> {@link IntentRepository#claimRecoverable} (QUOTING, APPROVED, COMPLETING, RECONCILING
 * and any intent with {@code cancel_pending}, whose lease is free or expired) and {@link RecoveryScan#approvalsDue}
 * (AWAITING_APPROVAL with an expired approval). Intents found by the scan are not claimed here: {@code advance} takes
 * the lease itself.
 *
 * <p><b>Driving one intent.</b> Before each {@code advance} of a claimed intent the lease is renewed
 * ({@code renewLease}); if that, or a fenced write inside {@code advance}, reports the lease lost
 * ({@link LeaseLostException}, {@link StaleVersionException}), the intent is dropped for this pass and not retried: the
 * worker that now holds it owns it. After a step the worker waits for {@code nextAttemptAt} through the injected
 * {@link Sleeper}; {@code advance} itself never sleeps. The series ends when {@code advance} reports idle.
 *
 * <p><b>Who runs it.</b> Drivers run on the injected {@link Executor}: a direct executor in tests, a small
 * platform-thread pool in production. No lock is held while waiting or while calling the merchant (7.8). The worker id
 * is unique per process start and comes from {@link #newWorkerId}; it is never a configured name, so a paused process
 * and its restarted successor are never the same lease owner.
 *
 * <p><b>Driving one intent now.</b> {@link #driveNow} starts a driver for one named intent at once, under the same claim
 * and lease rules as a pass (m6-plan section 12): the service calls it after an approval, so the first complete does not
 * wait for the next pass. A pass and {@code driveNow} share the set of intents in flight, so this worker never runs two
 * drivers for one intent; the lease keeps any other worker out (I1, I7).
 */
public final class RecoveryWorker {

    private static final System.Logger LOG = System.getLogger(RecoveryWorker.class.getName());

    private final IntentRepository intents;
    private final RecoveryScan scan;
    private final Orchestrator orchestrator;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Executor executor;
    private final RecoveryConfig config;
    private final String workerId;
    /** Intents this worker is driving now, so a later pass cannot start a second driver for one of them. */
    private final Set<IntentId> inFlight = ConcurrentHashMap.newKeySet();
    /** Intents whose next step is not due yet: a wait too long to sleep through. Per process; a restart starts clean. */
    private final Map<IntentId, Instant> notBefore = new ConcurrentHashMap<>();

    /**
     * @param orchestrator must use {@code workerId} as its lease owner, so the lease this worker claims is the one the
     *     orchestrator's fenced writes check
     */
    public RecoveryWorker(
            IntentRepository intents, RecoveryScan scan, Orchestrator orchestrator, Clock clock, Sleeper sleeper,
            Executor executor, RecoveryConfig config, String workerId) {
        this.intents = Objects.requireNonNull(intents, "intents");
        this.scan = Objects.requireNonNull(scan, "scan");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.config = Objects.requireNonNull(config, "config");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
    }

    /** A lease owner id unique to this process start (7.6 "Worker id"). */
    public static String newWorkerId(IdGenerator ids) {
        return "worker-" + Objects.requireNonNull(ids, "ids").next();
    }

    public String workerId() {
        return workerId;
    }

    /** For tests: how many intents this worker has parked for a later pass. */
    int parkedCount() {
        return notBefore.size();
    }

    /**
     * One pass. Returns the intents it started a driver for, in the order it picked them (oldest first, claimed ones
     * before those found by the scan). With a direct executor every driver has finished when this returns.
     */
    public List<IntentId> runOnce() {
        // The scan takes no lease, so it goes first: a failure there strands nothing that was claimed.
        List<IntentId> due = scan.approvalsDue(clock.instant(), config.limit());
        Set<IntentId> parked = renewParked();
        List<PurchaseIntent> claimed = intents.claimRecoverable(workerId, config.lease(), config.limit(), parked);
        List<IntentId> picked = new ArrayList<>();
        for (PurchaseIntent intent : claimed) {
            picked.add(intent.id());
        }
        int claimedCount = picked.size();
        forgetDueUnclaimed(picked);
        for (IntentId id : due) {
            if (picked.size() < config.limit() && !picked.contains(id)) {
                picked.add(id);
            }
        }
        Instant now = clock.instant();
        List<IntentId> started = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            IntentId id = picked.get(i);
            boolean claimedHere = i < claimedCount;
            Instant notYet = notBefore.get(id);
            if (notYet != null) {
                if (now.isBefore(notYet)) {
                    continue;
                }
                notBefore.remove(id, notYet);
            }
            if (!inFlight.add(id)) {
                continue;
            }
            try {
                executor.execute(() -> {
                    try {
                        drive(id, claimedHere);
                    } finally {
                        inFlight.remove(id);
                    }
                });
                started.add(id);
            } catch (RuntimeException e) {
                // The executor refused the task: the intent keeps its lease until it lapses and the next pass finds it.
                inFlight.remove(id);
                LOG.log(System.Logger.Level.ERROR, "could not start a driver for intent {0}: {1}", id,
                        RecoveryLoop.reason(e));
            }
        }
        return List.copyOf(started);
    }

    /**
     * Starts a driver for {@code id} now, on the injected executor (7.6, m6-plan section 12). It claims the intent here,
     * as a pass would ({@link IntentRepository#claim}: free, expired or already held by this worker), and only for an
     * intent a pass would drive: QUOTING, APPROVED, COMPLETING, RECONCILING, or one with {@code cancel_pending}. An
     * intent parked for a later attempt (7.5) waits for its time. Nothing is started, and nothing thrown, when this worker
     * is already driving the intent, another worker holds a live lease, the intent is in any other state, or it does not
     * exist; the next pass remains the fallback.
     *
     * @return true when a driver was started
     */
    public boolean driveNow(IntentId id) {
        Objects.requireNonNull(id, "id");
        Instant parkedUntil = notBefore.get(id);
        if (parkedUntil != null && clock.instant().isBefore(parkedUntil)) {
            return false;
        }
        if (!inFlight.add(id)) {
            return false;
        }
        boolean started = false;
        try {
            Optional<PurchaseIntent> intent = intents.findForSystem(id);
            if (intent.isEmpty() || !drivable(intent.get())
                    || intents.claim(id, workerId, config.lease()).isEmpty()) {
                return false;
            }
            notBefore.remove(id);
            executor.execute(() -> {
                try {
                    drive(id, true);
                } finally {
                    inFlight.remove(id);
                }
            });
            started = true;
            return true;
        } catch (RuntimeException e) {
            // A failed read or claim, or an executor that refused the task: the next pass picks the intent up.
            LOG.log(System.Logger.Level.ERROR, "could not drive intent {0} now: {1}", id, RecoveryLoop.reason(e));
            return false;
        } finally {
            if (!started) {
                inFlight.remove(id);
            }
        }
    }

    /** The intents {@link IntentRepository#claimRecoverable} returns: what a pass would drive (7.6). */
    private static boolean drivable(PurchaseIntent intent) {
        return switch (intent.state()) {
            case QUOTING, APPROVED, COMPLETING, RECONCILING -> true;
            default -> intent.cancelPending();
        };
    }

    /**
     * Renews the lease of each parked intent that is not due and returns them, so the claim can leave them out: they
     * would otherwise fill the pass limit pass after pass and keep other recoverable intents from being picked up. A
     * parked intent whose lease is gone is forgotten; the claim finds it again like any other. Due ones are not
     * returned: the claim takes them, and the pass drives them.
     *
     * <p>One write per parked intent per pass: fine for one SQLite instance, worth batching at M6/M7 scale. A database
     * error in one renewal aborts the whole pass, which is harmless: nothing was claimed yet, and the next pass retries.
     */
    private Set<IntentId> renewParked() {
        Instant now = clock.instant();
        Set<IntentId> notDue = new java.util.HashSet<>();
        for (Map.Entry<IntentId, Instant> e : notBefore.entrySet()) {
            if (!now.isBefore(e.getValue())) {
                continue;
            }
            if (intents.renewLease(e.getKey(), workerId, config.lease())) {
                notDue.add(e.getKey());
            } else {
                notBefore.remove(e.getKey(), e.getValue());
            }
        }
        return Set.copyOf(notDue);
    }

    /**
     * Forgets every parked intent that is due but that the claim did not return: it has gone terminal, or another worker
     * holds it. A due entry is never skipped, so forgetting it only means a later claim treats the intent like any other.
     * A parked intent that stopped being recoverable before it was due is still renewed until then (renewLease does not
     * look at the state, and a read per parked intent per pass is not worth it); it is dropped here once it is due.
     */
    private void forgetDueUnclaimed(List<IntentId> claimed) {
        Instant now = clock.instant();
        notBefore.entrySet().removeIf(e -> !now.isBefore(e.getValue()) && !claimed.contains(e.getKey()));
    }

    private void drive(IntentId id, boolean leased) {
        boolean holdsLease = leased;
        for (int step = 0; step < config.maxStepsPerIntent(); step++) {
            try {
                if (holdsLease && !intents.renewLease(id, workerId, config.lease())) {
                    LOG.log(System.Logger.Level.INFO, "lease lost for intent {0}; dropping it for this pass", id);
                    return;
                }
                StepResult result = orchestrator.advance(id);
                holdsLease = true;
                if (result.idle()) {
                    return;
                }
                if (!waitUntil(id, result.nextAttemptAt())) {
                    return;
                }
            } catch (LeaseLostException | StaleVersionException e) {
                LOG.log(System.Logger.Level.INFO, "intent {0} was taken over mid-step ({1}); dropping it for this pass",
                        id, e.getClass().getSimpleName());
                return;
            } catch (RuntimeException e) {
                // Not the lease: the intent is left for the next pass. Only a fixed reason is logged, never a message
                // that could carry a request body or a credential.
                LOG.log(System.Logger.Level.ERROR, "advance failed for intent {0}: {1}", id, RecoveryLoop.reason(e));
                return;
            }
        }
        LOG.log(System.Logger.Level.WARNING, "intent {0} used {1} steps in one pass; leaving it for the next",
                id, config.maxStepsPerIntent());
    }

    /**
     * Sleeps until {@code next} when it is in the future and no longer than half the lease. False when the driver
     * should stop for this pass: the wait is longer than that (the intent is parked until due), or it was interrupted.
     */
    private boolean waitUntil(IntentId id, Optional<Instant> next) {
        if (next.isEmpty()) {
            return true;
        }
        Duration wait = Duration.between(clock.instant(), next.get());
        if (wait.isNegative() || wait.isZero()) {
            return true;
        }
        if (wait.compareTo(config.lease().dividedBy(2)) > 0) {
            notBefore.put(id, next.get());
            return false;
        }
        try {
            sleeper.sleep(wait);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
