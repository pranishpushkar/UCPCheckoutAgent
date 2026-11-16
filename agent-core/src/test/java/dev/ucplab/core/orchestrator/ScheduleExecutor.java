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
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantGateways;
import dev.ucplab.core.merchant.fake.MerchantCall;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import dev.ucplab.core.merchant.fake.FaultRule;
import java.net.URI;
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
    /** A lease is free once lease_until is in the past; the clock's resolution makes that 1 ns after it (7.6). */
    static final Duration CLAIM_GRANULARITY = Duration.ofNanos(1);
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
    private final boolean spread;
    /** H1 C6: the merchant runs in fulfillment mode, so every intent goes through the shipping selection. */
    private boolean fulfillment;
    /** H5 C7a: every intent starts at an owned handoff merchant (7.2, 19), whose answers carry a continue_url. */
    private boolean handoff;
    private final FaultyMerchantModel.Mode mode;
    private final java.util.function.Function<dev.ucplab.core.MutableClock, CrashHarness.Stores> storeFactory;

    /** Runs schedules against a merchant in {@code mode} (14.1: every property runs in both modes). */
    ScheduleExecutor(WorkerFactory factory, FaultyMerchantModel.Mode mode) {
        this(factory, mode, Lab::inMemoryStores);
    }

    /** The same over the store {@code storeFactory} builds for each schedule's clock (the SQL adapter, M3). */
    ScheduleExecutor(WorkerFactory factory, FaultyMerchantModel.Mode mode,
            java.util.function.Function<dev.ucplab.core.MutableClock, CrashHarness.Stores> storeFactory) {
        this(factory, mode, storeFactory, false);
    }

    /**
     * {@code spread}: two merchants (I11, 8.5). The slots alternate between {@link Lab#SHOP} and {@link Lab#SHOP_B}, the
     * orchestrator picks each intent's gateway by its merchant, and every fault, price, stock change and crash of a
     * schedule applies to both merchants, so each runs the whole schedule's faults for the slots it holds.
     */
    static ScheduleExecutor withFulfillment(FaultyMerchantModel.Mode mode) {
        ScheduleExecutor e = new ScheduleExecutor(DEFAULT_ORCHESTRATOR, mode);
        e.fulfillment = true;
        return e;
    }

    /**
     * The handoff runs (H5 C7a, 14.1): merchant A's entry is an owned MCP store whose completion is handoff, with the
     * shop domain {@link #FLIP_SHOP_DOMAIN}; every answer carries a continue_url on it and a GET shows the store's
     * prices now ({@link ContinueUrlGateway}), so an approved intent hands off, or re-quotes when a price moved. A fault
     * the generator anchors on the nth complete, which a handoff intent never sends, goes on the nth GET instead. A
     * flip to any other kind changes the fingerprint (merchant_changed).
     */
    static ScheduleExecutor atHandoff(FaultyMerchantModel.Mode mode) {
        return atHandoff(mode, Lab::inMemoryStores);
    }

    /** The same over the store {@code storeFactory} builds (the SQL adapter, port parity, H5 C7b). */
    static ScheduleExecutor atHandoff(FaultyMerchantModel.Mode mode,
            java.util.function.Function<dev.ucplab.core.MutableClock, CrashHarness.Stores> storeFactory) {
        ScheduleExecutor e = new ScheduleExecutor(DEFAULT_ORCHESTRATOR, mode, storeFactory);
        e.handoff = true;
        return e;
    }

    /**
     * Whether the intent was proposed under an entry whose completion is handoff (its fingerprint, written once at
     * insert): the handoff MCP store of {@link #atHandoff} or a FlipEntry's HANDOFF kind, in any run.
     */
    static boolean atHandoffEntry(PurchaseIntent intent) {
        return intent.merchantFingerprint().contains("\"completion\":\"handoff\"");
    }

    static MerchantEntry handoffEntry(MerchantId id) {
        return new MerchantEntry(id, URI.create("http://127.0.0.1:8081"), "Shop", MerchantStatus.ACTIVE, Transport.MCP,
                Completion.HANDOFF, true, Optional.of(FLIP_SHOP_DOMAIN));
    }

    static ScheduleExecutor spread(FaultyMerchantModel.Mode mode) {
        return new ScheduleExecutor(DEFAULT_ORCHESTRATOR, mode, Lab::inMemoryStores, true);
    }

    private ScheduleExecutor(WorkerFactory factory, FaultyMerchantModel.Mode mode,
            java.util.function.Function<dev.ucplab.core.MutableClock, CrashHarness.Stores> storeFactory,
            boolean spread) {
        this.spread = spread;
        this.factory = factory;
        this.mode = mode;
        this.storeFactory = storeFactory;
    }

    Observations run(Schedule schedule) {
        return new Execution().run(schedule).getFirst();
    }

    /**
     * What each merchant saw: one {@link Observations} for a single merchant, and for a spread run (two merchants) one per
     * merchant, each holding that merchant's slots, intents, calls, log and orders, so every invariant is checked in the
     * single-merchant world it is written for, over the same shared clock, workers, leases and crashes.
     */
    List<Observations> runViews(Schedule schedule) {
        return new Execution().run(schedule);
    }

    /**
     * The registry entry a FlipEntry sets (I12): OWNED is the lab entry every intent starts under; NOT_OWNED, HANDOFF
     * and SHOP_DOMAIN each differ from it in one fingerprint field, MCP from SHOP_DOMAIN in transport alone.
     */
    static final String FLIP_SHOP_DOMAIN = "shop.myshopify.com";

    static MerchantEntry entryOf(MerchantId id, ScheduleEntry.Registry kind) {
        URI url = URI.create("http://127.0.0.1:8081");
        return switch (kind) {
            case OWNED -> new MerchantEntry(id, url, "Shop", MerchantStatus.ACTIVE);
            case NOT_OWNED -> new MerchantEntry(id, url, "Shop", MerchantStatus.ACTIVE, Transport.REST,
                    Completion.AGENT, false, Optional.empty());
            case HANDOFF -> new MerchantEntry(id, url, "Shop", MerchantStatus.ACTIVE, Transport.REST,
                    Completion.HANDOFF, true, Optional.empty());
            case SHOP_DOMAIN -> new MerchantEntry(id, url, "Shop", MerchantStatus.ACTIVE, Transport.REST,
                    Completion.AGENT, true, Optional.of(FLIP_SHOP_DOMAIN));
            case MCP -> new MerchantEntry(id, url, "Shop", MerchantStatus.ACTIVE, Transport.MCP,
                    Completion.AGENT, true, Optional.of(FLIP_SHOP_DOMAIN));
        };
    }

    /** A run was cut short; the message says why. */
    private static final class Abort extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Abort(String message) {
            super(message);
        }
    }

    private final class Execution {
        private final Lab lab = new Lab(OrchestratorConfig.defaults(), mode, storeFactory);
        {
            if (fulfillment) {
                lab.merchant.enableFulfillment();
                lab.merchantB.enableFulfillment();
            }
        }

        private final AtomicInteger seq = new AtomicInteger();
        /** The registry entry of each merchant now (I12); a FlipEntry changes it. The orchestrators read it each step. */
        private final Map<MerchantId, MerchantEntry> entryNow = new HashMap<>(Map.of(
                Lab.SHOP, entryOf(Lab.SHOP, ScheduleEntry.Registry.OWNED),
                Lab.SHOP_B, entryOf(Lab.SHOP_B, ScheduleEntry.Registry.OWNED)));
        {
            if (handoff) {
                entryNow.put(Lab.SHOP, handoffEntry(Lab.SHOP));
            }
            lab.entries = id -> Optional.ofNullable(entryNow.get(id));
        }
        /** The intent whose step runs now, or the slot being proposed (its intent is stored before its create goes). */
        private Optional<IntentId> stepping = Optional.empty();
        private Optional<String> proposing = Optional.empty();
        private final List<String> ownStoreViolations = new ArrayList<>();
        /** For the I12 reach numbers: a flip has been applied, and mutating calls that reached the merchant since. */
        private boolean flipped;
        private int callsAfterFlip;
        private final ProbeGateway probe = new ProbeGateway(lab.merchant, lab.clock, seq);
        private final Map<Integer, IntentId> slots = new HashMap<>();
        private final Map<IntentId, Instant> due = new LinkedHashMap<>();
        private final Map<IntentId, Map<String, String>> tokensSeen = new HashMap<>();
        private final List<Observations.Decision> decisions = new ArrayList<>();
        private final List<Observations.CrashEvent> crashes = new ArrayList<>();
        private final List<Observations.Sample> samples = new ArrayList<>();
        private final List<String> aborts = new ArrayList<>();
        private final ProbeGateway probeB = new ProbeGateway(lab.merchantB, lab.clock, seq);
        private InterceptingGateway gateway;
        private InterceptingGateway gatewayB;
        private Worker worker;
        private int workers;
        private int steps;

        List<Observations> run(Schedule schedule) {
            schedule.entries().stream()
                    .filter(ScheduleEntry.CallFault.class::isInstance).map(ScheduleEntry.CallFault.class::cast)
                    .map(f -> handoff && f.call() == MerchantCall.COMPLETE
                            ? new ScheduleEntry.CallFault(MerchantCall.GET, f.nth(), f.fault()) : f)
                    .forEach(f -> {
                        lab.merchant.addRule(FaultRule.nth(f.call(), f.nth(), f.fault()));
                        if (spread) {
                            lab.merchantB.addRule(FaultRule.nth(f.call(), f.nth(), f.fault()));
                        }
                    });
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
            List<String> reasons = slots.values().stream()
                    .flatMap(id -> lab.intent(id).failureReason().stream()).toList();
            // A send I12 stopped: an intent escalated at a send or step, or an owed cancel blocked (events: in memory only).
            long refused = reasons.stream().filter(r -> r.equals(OwnStoreGuard.MERCHANT_CHANGED)
                    || r.equals(OwnStoreGuard.MERCHANT_NOT_OWNED)).count();
            if (lab.intents instanceof dev.ucplab.core.intent.InMemoryIntentRepository) {
                refused += slots.values().stream().flatMap(id -> lab.events(id).stream())
                        .filter(e -> e.type().equals(OwnStoreGuard.CANCEL_BLOCKED)).count();
            }
            Observations.OwnStore ownStore = new Observations.OwnStore(ownStoreViolations, reasons, flipped,
                    callsAfterFlip, (int) refused);
            lab.clock.advance(SETTLE);
            lab.merchant.settle();
            if (spread) {
                lab.merchantB.settle();
                return List.of(view(false, ownStore), view(true, ownStore));
            }
            Map<IntentId, List<dev.ucplab.core.intent.AttemptOp>> ops = new HashMap<>();
            for (IntentId id : slots.values()) {
                if (lab.intents.findForSystem(id).filter(ScheduleExecutor::atHandoffEntry).isPresent()) {
                    ops.put(id, lab.intents.attempts(id).stream().map(dev.ucplab.core.intent.MerchantAttempt::op)
                            .toList());
                }
            }
            return List.of(new Observations(slots, probe.calls(), lab.merchant.log(), lab.merchant.orders(), decisions,
                    crashes, samples, aborts, lab.config, ownStore).withAttemptOps(ops));
        }

        private void apply(ScheduleEntry entry) {
            switch (entry) {
                case ScheduleEntry.Propose p -> propose(p.slot());
                case ScheduleEntry.Approve a -> decide(a.slot(), true, false);
                case ScheduleEntry.ApproveStale a -> decide(a.slot(), true, true);
                case ScheduleEntry.Reject r -> decide(r.slot(), false, false);
                case ScheduleEntry.Wait w -> runUntil(Optional.of(lab.clock.instant().plus(w.duration())));
                case ScheduleEntry.SetPrice p -> {
                    lab.merchant.setPrice(p.itemId(), p.priceMinor());
                    if (spread) {
                        lab.merchantB.setPrice(p.itemId(), p.priceMinor());
                    }
                }
                case ScheduleEntry.SetStock s -> {
                    lab.merchant.setStock(s.itemId(), s.stock());
                    if (spread) {
                        lab.merchantB.setStock(s.itemId(), s.stock());
                    }
                }
                case ScheduleEntry.CallFault f -> { } // installed before the first entry
                case ScheduleEntry.FlipEntry f -> {
                    entryNow.replaceAll((m, e) -> entryOf(m, f.entry()));
                    flipped = true;
                }
                case ScheduleEntry.Crash c -> {
                    for (InterceptingGateway g : spread ? List.of(gateway, gatewayB) : List.of(gateway)) {
                        if (c.afterCall()) {
                            g.crashAfterNext(c.call());
                        } else {
                            g.crashBeforeNext(c.call());
                        }
                    }
                }
            }
        }

        private void propose(int slot) {
            List<ProposedLine> lines = SLOT_LINES.get(slot);
            MerchantId merchant = merchantOf(slot);
            Optional<IntentId> existing = Optional.ofNullable(slots.get(slot));
            if (existing.isPresent() && lab.intent(existing.get()).state().isTerminal()) {
                return;
            }
            IntentId id;
            proposing = Optional.of(DefaultOrchestrator.dedupeKey(Lab.ALICE, merchant, lines));
            try {
                id = worker.orchestrator().propose(lab.alice(), merchant, lines, "property schedule").intentId();
            } catch (InterceptingGateway.SimulatedCrash e) {
                String dedupe = DefaultOrchestrator.dedupeKey(Lab.ALICE, merchant, lines);
                id = lab.intents.findActiveByDedupeKey(dedupe)
                        .orElseThrow(() -> new Abort("crash in propose left no intent for slot " + slot)).id();
                crashed(id, e);
            } finally {
                proposing = Optional.empty();
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
            stepping = Optional.of(id);
            try {
                result = worker.orchestrator().advance(id);
            } catch (InterceptingGateway.SimulatedCrash e) {
                crashed(id, e);
                sample(id, Observations.Phase.AFTER_STEP);
                due.put(id, lab.clock.instant());
                return;
            } finally {
                stepping = Optional.empty();
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
                    // claim needs lease_until strictly in the past: the earliest takeover is 1 ns later (7.6).
                    due.put(id, leasedUntil.get().plus(CLAIM_GRANULARITY));
                } else if (expiry.isPresent()) {
                    due.put(id, expiry.get());
                } else if (atHandoffEntry(intent) && intent.state() == dev.ucplab.core.intent.IntentState.APPROVED) {
                    // 7.2: a failed hand_off read waits for the recovery worker's next pass (7.6), one period later.
                    due.put(id, now.plus(Invariants.WORKER_PASS));
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
            gateway = new InterceptingGateway(checked(handoff
                    ? new ContinueUrlGateway(probe, FLIP_SHOP_DOMAIN, lab.merchant::price) : probe));
            if (spread) {
                gatewayB = new InterceptingGateway(checked(probeB));
                InterceptingGateway.link(gateway, gatewayB);
                Lab.Worker w = lab.routedWorker(currentWorkerId(),
                        MerchantGateways.of(Map.of(Lab.SHOP, gateway, Lab.SHOP_B, gatewayB)));
                worker = new Worker(w.orchestrator(), w.approvalService());
                return;
            }
            worker = factory.create(lab, currentWorkerId(), gateway);
        }

        /** The I12 catch-all where calls reach the merchant: after a crash-before, before the probe and the merchant. */
        private MerchantGateway checked(MerchantGateway merchantSide) {
            return new OwnStoreCheckGateway(merchantSide, id -> Optional.ofNullable(entryNow.get(id)), this::acting,
                    i -> lab.intents.attempts(i.id()), ownStoreViolations, () -> {
                        if (flipped) {
                            callsAfterFlip++;
                        }
                    });
        }

        private Optional<PurchaseIntent> acting() {
            if (stepping.isPresent()) {
                return lab.intents.findForSystem(stepping.get());
            }
            return proposing.flatMap(lab.intents::findActiveByDedupeKey);
        }

        /** Slots alternate between the two merchants when spread; otherwise every slot is at the one merchant. */
        private MerchantId merchantOf(int slot) {
            return spread && slot % 2 == 1 ? Lab.SHOP_B : Lab.SHOP;
        }

        /** What merchant A (or B) saw: its slots' intents, its calls, its log and its orders; every crash; the aborts go with A. */
        private Observations view(boolean b, Observations.OwnStore ownStore) {
            Map<Integer, IntentId> mine = new HashMap<>();
            slots.forEach((slot, id) -> {
                if (merchantOf(slot).equals(b ? Lab.SHOP_B : Lab.SHOP)) {
                    mine.put(slot, id);
                }
            });
            java.util.Set<IntentId> ids = new java.util.HashSet<>(mine.values());
            FaultyMerchantModel model = b ? lab.merchantB : lab.merchant;
            return new Observations(mine, (b ? probeB : probe).calls(), model.log(), model.orders(),
                    decisions.stream().filter(d -> ids.contains(d.intent())).toList(),
                    crashes,   // a crash kills the process: it matters to every intent, whichever merchant it was at
                    samples.stream().filter(x -> ids.contains(x.intent())).toList(), b ? List.of() : aborts, lab.config,
                    b ? Observations.OwnStore.NONE : ownStore);   // the violations go with A, as the aborts do
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
