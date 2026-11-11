package dev.ucplab.core.orchestrator;

import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.crypto.Sha256;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.fake.CallRecord;
import dev.ucplab.core.merchant.fake.MerchantCall;
import dev.ucplab.core.merchant.fake.ModelOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The invariants of 4.3 checked over one run's {@link Observations} (section 14, level 2). Each check returns its
 * violations, empty when the run is clean. Evidence comes from the merchant side (orders, the merchant's call log, the
 * wire in front of it) and from the user's own decisions, never from what the orchestrator reports (13.1). A run that
 * was aborted fails every check, because none of them could be established.
 *
 * <p>Attribution without trusting the orchestrator: an idempotency key belongs to the intent it is derived from (7.4),
 * computed here for every op and revision up to {@link #MAX_REVISION}; an order belongs to the intent whose basket its
 * lines match (each slot's basket is distinct, {@link ScheduleExecutor#SLOT_LINES}). A request or order that maps to
 * no intent is itself a violation.
 */
final class Invariants {

    /** P8 allows 2 re-quotes, so a correct run uses complete revisions 0 to 2; the margin catches a runaway counter. */
    static final int MAX_REVISION = 8;
    /** One RecoveryWorker pass (7.6: it runs every 5 s). */
    static final Duration WORKER_PASS = Duration.ofSeconds(5);
    /** The request timeout of create and update (8.3); complete's is in OrchestratorConfig. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    /** 8.2 retry backoff: exponential from 200 ms, factor 2, capped at 2 s, full jitter (so at most the capped delay). */
    /** In-call retries of the gateway (8.2). The orchestrator does not retry QUOTING calls (7.2), so this is the QUOTING bound. */
    static final int GATEWAY_RETRIES = 3;
    static final Duration BACKOFF_BASE = Duration.ofMillis(200);
    static final Duration BACKOFF_CAP = Duration.ofSeconds(2);

    private Invariants() {}

    // ---------------------------------------------------------------- I1

    /** I1: the merchant holds at most one order per intent. */
    static List<String> i1(Observations o) {
        List<String> v = new ArrayList<>(o.aborts());
        Map<IntentId, List<ModelOrder>> byIntent = new LinkedHashMap<>();
        for (ModelOrder order : o.orders()) {
            Optional<IntentId> owner = ownerOfLines(o, order.lines());
            if (owner.isEmpty()) {
                v.add("I1: order " + order.orderId() + " matches no intent's basket: " + lines(order.lines()));
            } else {
                byIntent.computeIfAbsent(owner.get(), k -> new ArrayList<>()).add(order);
            }
        }
        byIntent.forEach((id, orders) -> {
            if (orders.size() > 1) {
                v.add("I1: intent " + id.value() + " has " + orders.size() + " orders: " + orders.stream()
                        .map(r -> r.orderId() + " on " + r.sessionId().value() + " at " + r.createdAt()).toList());
            }
        });
        return v;
    }

    // ---------------------------------------------------------------- I3

    /**
     * I3: every {@code complete} the merchant received follows the user's approval of that intent, given before the
     * approval expired, with a hash that matches its snapshot, not consumed by another key, and whose snapshot equals
     * the quote the merchant holds for the session at that moment. A replayed token must never be accepted.
     */
    static List<String> i3(Observations o) {
        List<String> v = new ArrayList<>(o.aborts());
        o.decisions().stream().filter(d -> d.approve() && d.staleToken()).forEach(d -> v.add(
                "I3: the approval service accepted a token from an earlier approval for " + d.intent().value()
                        + " at " + d.at()));
        Map<IdempotencyKey, KeyOwner> owners = keyOwners(o);
        // Approvals are told apart by the user's decision, not the hash: two approvals of the same intent can have
        // byte-identical snapshots (same lines, total and expires_at when re-quotes happen at one instant).
        Map<Integer, IdempotencyKey> keyOfApproval = new HashMap<>();
        for (Observations.ClientCall c : completes(o)) {
            Optional<KeyOwner> owner = c.key().map(owners::get);
            if (owner.isEmpty()) {
                v.add("I3: complete #" + c.seq() + " carries a key that belongs to no intent: " + c.key());
                continue;
            }
            IntentId id = owner.get().intent();
            String where = "complete #" + c.seq() + " for " + id.value() + " at " + c.at();
            Optional<Observations.Decision> last = lastDecision(o, id, c.seq());
            if (last.isEmpty() || !last.get().approve()) {
                v.add("I3: " + where + " was sent without an approval"
                        + (last.isPresent() ? " (the user's last decision was a rejection)" : ""));
                continue;
            }
            Observations.Decision approval = last.get();
            ApprovalSnapshot snapshot = approval.snapshot();
            if (!snapshot.hash().equals(approval.snapshotHash())) {
                v.add("I3: " + where + ": the approved hash does not match its snapshot");
            }
            if (!approval.at().isBefore(snapshot.expiresAt())) {
                v.add("I3: " + where + ": approved at " + approval.at() + ", after it expired at " + snapshot.expiresAt());
            }
            IdempotencyKey bound = keyOfApproval.putIfAbsent(approval.seq(), c.key().get());
            if (bound != null && !bound.equals(c.key().get())) {
                v.add("I3: " + where + " uses an approval already consumed by key " + bound.value());
            }
            if (c.sessionBefore().isEmpty()) {
                v.add("I3: " + where + " targets " + c.session() + ", which the merchant does not have");
                continue;
            }
            CheckoutView quote = c.sessionBefore().get();
            boolean matches = quote.id().equals(snapshot.sessionId())
                    && lines(quote.lines()).equals(snapshotLines(snapshot))
                    && quote.currency().equals(snapshot.currency())
                    && quote.totalMinor() == snapshot.totalMinor();
            if (!matches) {
                v.add("I3: " + where + ": approved " + snapshot.sessionId().value() + " " + snapshotLines(snapshot)
                        + " total " + snapshot.totalMinor() + ", but the merchant holds " + quote.id().value() + " "
                        + lines(quote.lines()) + " total " + quote.totalMinor());
            }
        }
        return v;
    }

    // ---------------------------------------------------------------- I4

    /**
     * I4: after an intent's first {@code complete} reached the merchant, no request for it targets another session,
     * no create is sent or newly processed for it, and its stored session id stays the one that complete targeted.
     */
    static List<String> i4(Observations o) {
        List<String> v = new ArrayList<>(o.aborts());
        Map<IdempotencyKey, KeyOwner> owners = keyOwners(o);
        for (IntentId id : o.intentsBySlot().values()) {
            List<Observations.ClientCall> mine = o.calls().stream()
                    .filter(c -> c.key().map(owners::get).map(k -> k.intent().equals(id)).orElse(false))
                    .toList();
            Optional<Observations.ClientCall> first = mine.stream()
                    .filter(c -> c.call() == MerchantCall.COMPLETE).findFirst();
            if (first.isEmpty()) {
                continue;
            }
            SessionId session = first.get().session().orElseThrow();
            for (Observations.ClientCall c : mine) {
                if (c.seq() <= first.get().seq()) {
                    continue;
                }
                if (c.call() == MerchantCall.CREATE) {
                    v.add("I4: create #" + c.seq() + " was sent for " + id.value() + " after its first complete #"
                            + first.get().seq());
                } else if (c.session().isPresent() && !c.session().get().equals(session)) {
                    v.add("I4: " + c.call() + " #" + c.seq() + " for " + id.value() + " targets "
                            + c.session().get().value() + " after the first complete went to " + session.value());
                }
            }

            int firstInLog = o.merchantLog().stream()
                    .filter(r -> !r.delivered() && r.call() == MerchantCall.COMPLETE && ownedBy(r, owners, id))
                    .mapToInt(CallRecord::seq).min().orElseThrow();
            Set<IdempotencyKey> createKeys = new HashSet<>();
            for (CallRecord r : o.merchantLog()) {
                if (r.delivered() && r.call() == MerchantCall.CREATE && ownedBy(r, owners, id)
                        && createKeys.add(r.key().orElseThrow()) && r.seq() > firstInLog) {
                    v.add("I4: the merchant processed a new create for " + id.value() + " at " + r.at()
                            + ", after its first complete");
                }
            }

            o.samples().stream()
                    .filter(s -> s.intent().equals(id) && s.seq() > first.get().seq())
                    .filter(s -> !s.session().equals(Optional.of(session)))
                    .findFirst()
                    .ifPresent(s -> v.add("I4: " + id.value() + " stored session " + s.session() + " at " + s.at()
                            + " after its first complete went to " + session.value()));
        }
        return v;
    }

    // ---------------------------------------------------------------- I5

    /**
     * I5 with the liveness bounds of 7.6: once the run drains every intent is terminal (ESCALATED included), and on
     * the way no intent stays in a state longer than its bound:
     * <ul>
     *   <li>PROPOSED: one worker pass;
     *   <li>QUOTING: one worker pass plus at most 4 attempts of one create or update and their backoff (8.2), about
     *       26 s (5 s + 4 &times; 5 s + 0.2 s + 0.4 s + 0.8 s);
     *   <li>APPROVED: one worker pass plus the first complete's 10 s timeout;
     *   <li>AWAITING_APPROVAL: the pending approval's {@code expires_at};
     *   <li>COMPLETING and RECONCILING, taken together: the reconciliation deadline from the step that entered them.
     *       A new complete key leaves them through QUOTING, so each key gets its own deadline (7.5 rule 1).
     * </ul>
     * Each crash inside a stretch adds at most one lease period plus one worker period (30 s + 5 s = 35 s) before
     * another worker takes over. A sample taken before a step may sit exactly on the bound (that
     * step must leave the state); one taken after a step may not.
     */
    static List<String> i5(Observations o) {
        List<String> v = new ArrayList<>(o.aborts());
        List<Instant> crashes = o.crashes().stream().map(Observations.CrashEvent::at).sorted().toList();
        Map<IntentId, List<Observations.Sample>> byIntent = o.samples().stream()
                .collect(Collectors.groupingBy(Observations.Sample::intent, LinkedHashMap::new, Collectors.toList()));
        byIntent.forEach((id, samples) -> {
            samples.stream().filter(s -> s.phase() == Observations.Phase.END).findFirst()
                    .filter(s -> !s.state().isTerminal())
                    .ifPresent(s -> v.add("I5: " + id.value() + " is still " + s.state() + " after the run drained"));
            Optional<String> stretch = Optional.empty();
            Instant start = Instant.MIN;
            Instant bound = Instant.MAX;
            for (Observations.Sample s : samples) {
                Optional<String> now = stretchOf(s.state());
                if (now.isEmpty()) {
                    stretch = now;
                    continue;
                }
                if (!now.equals(stretch)) {
                    stretch = now;
                    start = s.at();
                    bound = extendForCrashes(firstBound(s, o), start, crashes, o.config().lease());
                }
                boolean overdue = s.phase() == Observations.Phase.BEFORE_STEP
                        ? s.at().isAfter(bound)
                        : !s.at().isBefore(bound);
                if (overdue) {
                    v.add("I5: " + id.value() + " was still " + s.state() + " at " + s.at() + " (" + s.phase()
                            + "), past its bound " + bound + " for the " + now.get() + " stretch begun at " + start);
                    break;
                }
            }
        });
        return v;
    }

    /** The rows of the 7.6 table: each state is its own stretch, except COMPLETING and RECONCILING together. */
    private static Optional<String> stretchOf(IntentState state) {
        return switch (state) {
            case PROPOSED, QUOTING, APPROVED, AWAITING_APPROVAL -> Optional.of(state.name());
            case COMPLETING, RECONCILING -> Optional.of("COMPLETING/RECONCILING");
            case COMPLETED, REJECTED, FAILED_NOT_CHARGED, ESCALATED -> Optional.empty();
        };
    }

    private static Instant firstBound(Observations.Sample s, Observations o) {
        OrchestratorConfig c = o.config();
        return switch (s.state()) {
            case PROPOSED -> s.at().plus(WORKER_PASS);
            case QUOTING -> s.at().plus(WORKER_PASS).plus(REQUEST_TIMEOUT.multipliedBy(GATEWAY_RETRIES + 1L))
                    .plus(worstBackoff(GATEWAY_RETRIES));
            case APPROVED -> s.at().plus(WORKER_PASS).plus(c.completeRequestTimeout());
            case AWAITING_APPROVAL -> s.approvalExpiresAt().orElse(s.at().plus(DefaultApprovalService.DEFAULT_TTL));
            case COMPLETING, RECONCILING -> s.at().plus(c.reconcileDeadline());
            case COMPLETED, REJECTED, FAILED_NOT_CHARGED, ESCALATED -> Instant.MAX;
        };
    }

    private static Instant extendForCrashes(Instant bound, Instant start, List<Instant> crashes, Duration lease) {
        Instant b = bound;
        for (Instant crash : crashes) {
            if (!crash.isBefore(start) && !crash.isAfter(b)) {
                b = b.plus(lease).plus(WORKER_PASS);
            }
        }
        return b;
    }

    /** The longest the 8.2 backoff can wait before {@code retries} retries: the sum of the capped delays. */
    static Duration worstBackoff(int retries) {
        Duration total = Duration.ZERO;
        Duration delay = BACKOFF_BASE;
        for (int i = 0; i < retries; i++) {
            total = total.plus(delay.compareTo(BACKOFF_CAP) > 0 ? BACKOFF_CAP : delay);
            delay = delay.multipliedBy(2);
        }
        return total;
    }

    // ---------------------------------------------------------------- I7

    /**
     * I7: every {@code complete} sent under one approval carries the same key and a byte-identical body; a later
     * approval of the same intent uses a key no earlier approval used. An approval is the user's decision, not its
     * hash, because two approvals can have identical snapshots. Keys and body hashes are the merchant's own
     * log; the wire record only says which approval was in force, and must agree with the log call for call.
     */
    static List<String> i7(Observations o) {
        List<String> v = new ArrayList<>(o.aborts());
        List<CallRecord> logged = o.merchantLog().stream()
                .filter(r -> !r.delivered() && r.call() == MerchantCall.COMPLETE).toList();
        List<Observations.ClientCall> sent = completes(o);
        if (logged.size() != sent.size()) {
            v.add("I7: the merchant logged " + logged.size() + " complete calls but " + sent.size() + " were sent");
            return v;
        }
        Map<IdempotencyKey, KeyOwner> owners = keyOwners(o);
        Map<String, Set<IdempotencyKey>> keysByApproval = new LinkedHashMap<>();
        Map<IdempotencyKey, Set<String>> approvalsByKey = new LinkedHashMap<>();
        Map<IdempotencyKey, Set<String>> bodiesByKey = new LinkedHashMap<>();
        for (int i = 0; i < sent.size(); i++) {
            Observations.ClientCall c = sent.get(i);
            CallRecord r = logged.get(i);
            if (!r.key().equals(c.key()) || !r.bodySha256().equals(Sha256.hex(c.body()))) {
                v.add("I7: complete #" + c.seq() + " differs from the merchant's log record " + r.seq());
                return v;
            }
            IdempotencyKey key = r.key().orElseThrow();
            KeyOwner owner = owners.get(key);
            if (owner == null) {
                v.add("I7: complete #" + c.seq() + " carries a key that belongs to no intent: " + key.value());
                continue;
            }
            String approval = lastDecision(o, owner.intent(), c.seq())
                    .filter(Observations.Decision::approve)
                    .map(d -> owner.intent().value() + "/decision#" + d.seq() + "/" + d.snapshotHash().substring(0, 12))
                    .orElse(owner.intent().value() + "/no-approval");
            keysByApproval.computeIfAbsent(approval, k -> new LinkedHashSet<>()).add(key);
            approvalsByKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(approval);
            bodiesByKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(r.bodySha256());
        }
        keysByApproval.forEach((approval, keys) -> {
            if (keys.size() > 1) {
                v.add("I7: approval " + approval + " sent complete under " + keys.size() + " keys: "
                        + keys.stream().map(k -> describe(k, owners)).toList());
            }
        });
        approvalsByKey.forEach((key, approvals) -> {
            if (approvals.size() > 1) {
                v.add("I7: key " + describe(key, owners) + " was reused under approvals " + approvals);
            }
        });
        bodiesByKey.forEach((key, bodies) -> {
            if (bodies.size() > 1) {
                v.add("I7: key " + describe(key, owners) + " was sent with " + bodies.size() + " different bodies");
            }
        });
        return v;
    }

    // ---------------------------------------------------------------- attribution

    private record KeyOwner(IntentId intent, MutatingOp op, int revision) {}

    private static Map<IdempotencyKey, KeyOwner> keyOwners(Observations o) {
        Map<IdempotencyKey, KeyOwner> owners = new HashMap<>();
        for (IntentId id : o.intentsBySlot().values()) {
            for (MutatingOp op : MutatingOp.values()) {
                // 7.4: create and cancel always use revision 0.
                int maxRevision = op == MutatingOp.CREATE || op == MutatingOp.CANCEL ? 0 : MAX_REVISION;
                for (int revision = 0; revision <= maxRevision; revision++) {
                    owners.put(IdempotencyKey.derive(id, op, revision), new KeyOwner(id, op, revision));
                }
            }
        }
        return owners;
    }

    private static boolean ownedBy(CallRecord r, Map<IdempotencyKey, KeyOwner> owners, IntentId id) {
        return r.key().map(owners::get).map(k -> k.intent().equals(id)).orElse(false);
    }

    private static String describe(IdempotencyKey key, Map<IdempotencyKey, KeyOwner> owners) {
        KeyOwner owner = owners.get(key);
        return owner == null ? key.value() : owner.op().wireName() + " rev " + owner.revision() + " (" + key.value() + ")";
    }

    private static Optional<IntentId> ownerOfLines(Observations o, List<LineView> orderLines) {
        String basket = orderLines.stream().map(l -> l.itemId() + ":" + l.quantity()).sorted()
                .collect(Collectors.joining(","));
        for (Map.Entry<Integer, IntentId> slot : o.intentsBySlot().entrySet()) {
            List<ProposedLine> lines = ScheduleExecutor.SLOT_LINES.get(slot.getKey());
            String slotBasket = lines.stream().map(l -> l.itemId() + ":" + l.quantity()).sorted()
                    .collect(Collectors.joining(","));
            if (slotBasket.equals(basket)) {
                return Optional.of(slot.getValue());
            }
        }
        return Optional.empty();
    }

    private static List<Observations.ClientCall> completes(Observations o) {
        return o.calls().stream().filter(c -> c.call() == MerchantCall.COMPLETE).toList();
    }

    /** The user's last accepted decision on the intent before {@code seq}. */
    private static Optional<Observations.Decision> lastDecision(Observations o, IntentId id, int seq) {
        return o.decisions().stream().filter(d -> d.intent().equals(id) && d.seq() < seq).reduce((a, b) -> b);
    }

    private static List<String> lines(List<LineView> lines) {
        return lines.stream().map(l -> l.itemId() + "x" + l.quantity() + "@" + l.unitPriceMinor()).sorted().toList();
    }

    private static List<String> snapshotLines(ApprovalSnapshot snapshot) {
        return snapshot.lines().stream().map(l -> l.itemId() + "x" + l.quantity() + "@" + l.unitPriceMinor())
                .sorted().toList();
    }
}
