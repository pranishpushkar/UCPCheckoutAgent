package dev.ucplab.core.orchestrator;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.LeaseLostException;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.fake.FaultRule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs one {@link Schedule} against a fresh {@link Lab} and returns its {@link Observations} (section 14, level 2).
 *
 * <p>The executor plays the user and the RecoveryWorker of M3: it keeps a due time per intent, and while time passes
 * (a {@link ScheduleEntry.Wait}, or the final drain) it steps each intent when it falls due, moving the clock to the
 * returned {@code nextAttemptAt} (7.5 "Who waits"). An intent that is idle because a dead worker still holds its lease
 * falls due when the lease ends (7.6); an idle AWAITING_APPROVAL intent falls due when its approval expires (9.2).
 * After the last entry the run drains with no further user action, then the clock moves past every proxy hold so late
 * deliveries reach the merchant before its orders are read.
 *
 * <p>Each slot has a fixed basket with distinct lines, so a merchant order or session maps back to its slot by its
 * lines alone. A slot is proposed at most once into a new intent: a repeat while active exercises 7.7, a repeat after
 * a terminal state is skipped so the mapping stays one to one.
 */
final class ScheduleExecutor {

    static final List<List<ProposedLine>> SLOT_LINES = List.of(
            List.of(new ProposedLine("item_123", 1)),
            List.of(new ProposedLine("item_456", 2)),
            List.of(new ProposedLine("item_123", 2), new ProposedLine("item_456", 1)));

    static final int MAX_STEPS = 5_000;
    static final Duration HORIZON = Duration.ofHours(4);
    /** After the drain: longer than any proxy hold the generator makes. */
    static final Duration SETTLE = ScheduleGenerator.MAX_HOLD.plusMinutes(1);

    /** One orchestrator instance (worker) under test. */
    record Worker(Orchestrator orchestrator, DefaultApprovalService approvalService) {}

    /** Builds a worker over the lab's repositories, calling the merchant only through {@code gateway}. */
    @FunctionalInterface
    interface WorkerFactory {
        Worker create(Lab lab, String workerId, MerchantGateway gateway);
    }

    static final WorkerFactory DEFAULT_ORCHESTRATOR = (lab, workerId, gateway) -> {
        Lab.Worker w = lab.worker(workerId, gateway);
        return new Worker(w.orchestrator(), w.approvalService());
    };

    private final WorkerFactory factory;

    ScheduleExecutor(WorkerFactory factory) {
        this.factory = factory;
    }

    Observations run(Schedule schedule) {
        return new Execution().run(schedule);
    }

    /** A run was cut short; the message says why. */
    private static final class Abort extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Abort(String message) {
            super(message);
        }
    }

    private final class Execution {
        private final Lab lab = new Lab();
        private final AtomicInteger seq = new AtomicInteger();
        private final ProbeGateway probe = new ProbeGateway(lab.merchant, lab.clock, seq);
        private final Map<Integer, IntentId> slots = new HashMap<>();
        private final Map<IntentId, Instant> due = new LinkedHashMap<>();
        private final Map<IntentId, Map<String, String>> tokensSeen = new HashMap<>();
        private final List<Observations.Decision> decisions = new ArrayList<>();
        private final List<Observations.CrashEvent> crashes = new ArrayList<>();
        private final List<Observations.Sample> samples = new ArrayList<>();
        private final List<String> aborts = new ArrayList<>();
        private InterceptingGateway gateway;
        private Worker worker;
        private int workers;
        private int steps;

        Observations run(Schedule schedule) {
            schedule.entries().stream()
                    .filter(ScheduleEntry.CallFault.class::isInstance).map(ScheduleEntry.CallFault.class::cast)
                    .forEach(f -> lab.merchant.addRule(FaultRule.nth(f.call(), f.nth(), f.fault())));
            restart();
            try {
                for (ScheduleEntry entry : schedule.entries()) {
                    apply(entry);
                }
                runUntil(Optional.empty());
            } catch (Abort e) {
                aborts.add(e.getMessage());
            } catch (RuntimeException e) {
                aborts.add("the orchestrator threw at " + lab.clock.instant() + ": " + e);
            }
            slots.values().forEach(id -> sample(id, Observations.Phase.END));
            lab.clock.advance(SETTLE);
            lab.merchant.settle();
            return new Observations(slots, probe.calls(), lab.merchant.log(), lab.merchant.orders(), decisions,
                    crashes, samples, aborts, lab.config.reconcileDeadline(), lab.config.lease());
        }

        private void apply(ScheduleEntry entry) {
            switch (entry) {
                case ScheduleEntry.Propose p -> propose(p.slot());
                case ScheduleEntry.Approve a -> decide(a.slot(), true, false);
                case ScheduleEntry.ApproveStale a -> decide(a.slot(), true, true);
                case ScheduleEntry.Reject r -> decide(r.slot(), false, false);
                case ScheduleEntry.Wait w -> runUntil(Optional.of(lab.clock.instant().plus(w.duration())));
                case ScheduleEntry.SetPrice p -> lab.merchant.setPrice(p.itemId(), p.priceMinor());
                case ScheduleEntry.SetStock s -> lab.merchant.setStock(s.itemId(), s.stock());
                case ScheduleEntry.CallFault f -> { } // installed before the first entry
                case ScheduleEntry.Crash c -> {
                    if (c.afterCall()) {
                        gateway.crashAfterNext(c.call());
                    } else {
                        gateway.crashBeforeNext(c.call());
                    }
                }
            }
        }

        private void propose(int slot) {
            List<ProposedLine> lines = SLOT_LINES.get(slot);
            Optional<IntentId> existing = Optional.ofNullable(slots.get(slot));
            if (existing.isPresent() && lab.intent(existing.get()).state().isTerminal()) {
                return;
            }
            IntentId id;
            try {
                id = worker.orchestrator().propose(lab.alice(), Lab.SHOP, lines, "property schedule").intentId();
            } catch (InterceptingGateway.SimulatedCrash e) {
                String dedupe = DefaultOrchestrator.dedupeKey(Lab.ALICE, Lab.SHOP, lines);
                id = lab.intents.findActiveByDedupeKey(dedupe)
                        .orElseThrow(() -> new Abort("crash in propose left no intent for slot " + slot)).id();
                crashed(id, e);
            }
            if (existing.isPresent() && !existing.get().equals(id)) {
                throw new Abort("7.7: proposing slot " + slot + " again created a second active intent " + id);
            }
            slots.put(slot, id);
            sample(id, Observations.Phase.AFTER_STEP);
            due.put(id, lab.clock.instant());
        }

        private void decide(int slot, boolean approve, boolean stale) {
            IntentId id = slots.get(slot);
            if (id == null) {
                return;
            }
            Optional<PendingApproval> pending = worker.approvalService().pending(lab.alice(), id);
            if (pending.isEmpty()) {
                return;
            }
            String token = pending.get().token();
            String hash = pending.get().snapshotHash();
            tokensSeen.computeIfAbsent(id, k -> new LinkedHashMap<>()).putIfAbsent(hash, token);
            if (stale) {
                Optional<String> old = tokensSeen.getOrDefault(id, Map.of()).entrySet().stream()
                        .filter(e -> !e.getKey().equals(pending.get().snapshotHash()))
                        .map(Map.Entry::getValue).reduce((a, b) -> b);
                if (old.isEmpty()) {
                    return;
                }
                token = old.get();
            }
            ApprovalDecision decision;
            try {
                decision = worker.approvalService().decide(lab.alice(), id, token, approve);
            } catch (LeaseLostException e) {
                return; // a dead worker still holds the lease; the API would answer with an error
            }
            boolean accepted = decision instanceof ApprovalDecision.Approved
                    || decision instanceof ApprovalDecision.Rejected;
            if (accepted) {
                decisions.add(new Observations.Decision(seq.incrementAndGet(), lab.clock.instant(), id,
                        decision instanceof ApprovalDecision.Approved, pending.get().snapshot(), hash, stale));
                due.put(id, lab.clock.instant());
            }
        }

        /** The RecoveryWorker loop: step whatever is due up to {@code until} (or until nothing is due). */
        private void runUntil(Optional<Instant> until) {
            while (true) {
                Optional<Map.Entry<IntentId, Instant>> next = due.entrySet().stream()
                        .filter(e -> until.map(t -> !e.getValue().isAfter(t)).orElse(true))
                        .min(Map.Entry.comparingByValue(Comparator.naturalOrder()));
                if (next.isEmpty()) {
                    until.ifPresent(this::moveClockTo);
                    return;
                }
                moveClockTo(next.get().getValue());
                step(next.get().getKey());
            }
        }

        private void step(IntentId id) {
            if (++steps > MAX_STEPS) {
                throw new Abort("did not settle in " + MAX_STEPS + " steps");
            }
            if (lab.clock.instant().isAfter(Lab.T0.plus(HORIZON))) {
                throw new Abort("did not settle within " + HORIZON + " of simulated time");
            }
            sample(id, Observations.Phase.BEFORE_STEP);
            StepResult result;
            try {
                result = worker.orchestrator().advance(id);
            } catch (InterceptingGateway.SimulatedCrash e) {
                crashed(id, e);
                sample(id, Observations.Phase.AFTER_STEP);
                due.put(id, lab.clock.instant());
                return;
            }
            sample(id, Observations.Phase.AFTER_STEP);
            Instant now = lab.clock.instant();
            if (result.nextAttemptAt().isPresent()) {
                due.put(id, result.nextAttemptAt().get());
            } else if (!result.idle()) {
                due.put(id, now);
            } else {
                PurchaseIntent intent = lab.intent(id);
                Optional<Instant> leasedUntil = intent.leaseUntil()
                        .filter(t -> !t.isBefore(now) && !intent.leaseOwner().equals(Optional.of(currentWorkerId())));
                Optional<Instant> expiry = worker.approvalService().pending(lab.alice(), id)
                        .map(p -> p.snapshot().expiresAt()).filter(now::isBefore);
                if (!intent.state().isTerminal() && leasedUntil.isPresent()) {
                    due.put(id, leasedUntil.get().plusMillis(1));
                } else if (expiry.isPresent()) {
                    due.put(id, expiry.get());
                } else {
                    due.remove(id); // terminal, or stuck: I5 reports a stuck intent at the end
                }
            }
        }

        private void crashed(IntentId id, InterceptingGateway.SimulatedCrash e) {
            crashes.add(new Observations.CrashEvent(seq.incrementAndGet(), lab.clock.instant(), id, e.getMessage()));
            restart();
        }

        /** A fresh process: a new orchestrator and worker id over the same repositories and merchant (S9). */
        private void restart() {
            workers++;
            gateway = new InterceptingGateway(probe);
            worker = factory.create(lab, currentWorkerId(), gateway);
        }

        private String currentWorkerId() {
            return "worker-" + workers;
        }

        private void sample(IntentId id, Observations.Phase phase) {
            PurchaseIntent intent = lab.intent(id);
            Optional<PendingApproval> pending = worker.approvalService().pending(lab.alice(), id);
            pending.ifPresent(p -> tokensSeen.computeIfAbsent(id, k -> new LinkedHashMap<>())
                    .putIfAbsent(p.snapshotHash(), p.token()));
            samples.add(new Observations.Sample(seq.incrementAndGet(), lab.clock.instant(), id, phase, intent.state(),
                    intent.sessionId(), pending.map(p -> p.snapshot().expiresAt())));
        }

        private void moveClockTo(Instant t) {
            if (t.isAfter(lab.clock.instant())) {
                lab.clock.advance(Duration.between(lab.clock.instant(), t));
            }
        }
    }
}
