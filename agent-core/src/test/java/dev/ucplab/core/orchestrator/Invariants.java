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
    /** Steps the executor runs back to back (quote retries, an owed cancel) all happen at one instant. */
    static final Duration TRANSIENT_ALLOWANCE = Duration.ofSeconds(1);

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
        Map<String, IdempotencyKey> keyOfApproval = new HashMap<>();
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
            IdempotencyKey bound = keyOfApproval.putIfAbsent(approval.snapshotHash(), c.key().get());
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

    private enum Stretch { TRANSIENT, AWAITING_APPROVAL, UNCERTAIN }

    /**
     * I5: once the run drains, every intent is terminal (ESCALATED included), and no intent overstays a deadline on
     * the way. The deadlines: COMPLETING or RECONCILING for at most the reconciliation deadline from the step that
     * entered them (7.5); AWAITING_APPROVAL until the pending approval's expiry (9.2); PROPOSED, QUOTING and APPROVED
     * only for back-to-back steps. Each crash inside a stretch extends it by one lease, because the next worker must
     * wait for the dead one's lease to end (7.6). A sample taken before a step may sit exactly on the deadline (that
     * step must resolve it); one taken after a step may not.
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
            Optional<Stretch> stretch = Optional.empty();
            Instant start = Instant.MIN;
            Instant deadline = Instant.MAX;
            for (Observations.Sample s : samples) {
                Optional<Stretch> now = stretchOf(s.state());
                if (now.isEmpty()) {
                    stretch = now;
                    continue;
                }
                if (!now.equals(stretch)) {
                    stretch = now;
                    start = s.at();
                    deadline = extendForCrashes(firstDeadline(now.get(), s, o), start, crashes, o.lease());
                }
                boolean overdue = s.phase() == Observations.Phase.BEFORE_STEP
                        ? s.at().isAfter(deadline)
                        : !s.at().isBefore(deadline);
                if (overdue) {
                    v.add("I5: " + id.value() + " was still " + s.state() + " at " + s.at() + " (" + s.phase()
                            + "), past its deadline " + deadline + " for the " + now.get() + " stretch begun at "
                            + start);
                    break;
                }
            }
        });
        return v;
    }

    private static Optional<Stretch> stretchOf(IntentState state) {
        return switch (state) {
            case PROPOSED, QUOTING, APPROVED -> Optional.of(Stretch.TRANSIENT);
            case AWAITING_APPROVAL -> Optional.of(Stretch.AWAITING_APPROVAL);
            case COMPLETING, RECONCILING -> Optional.of(Stretch.UNCERTAIN);
            case COMPLETED, REJECTED, FAILED_NOT_CHARGED, ESCALATED -> Optional.empty();
        };
    }

    private static Instant firstDeadline(Stretch stretch, Observations.Sample s, Observations o) {
        return switch (stretch) {
            case TRANSIENT -> s.at().plus(TRANSIENT_ALLOWANCE);
            case AWAITING_APPROVAL -> s.approvalExpiresAt().orElse(s.at().plus(DefaultApprovalService.DEFAULT_TTL));
            case UNCERTAIN -> s.at().plus(o.reconcileDeadline());
        };
    }

    private static Instant extendForCrashes(Instant deadline, Instant start, List<Instant> crashes, Duration lease) {
        Instant d = deadline;
        for (Instant crash : crashes) {
            if (!crash.isBefore(start) && !crash.isAfter(d)) {
                d = d.plus(lease).plus(TRANSIENT_ALLOWANCE);
            }
        }
        return d;
    }

    // ---------------------------------------------------------------- I7

    /**
     * I7: every {@code complete} sent under one approval carries the same key and a byte-identical body; a later
     * approval of the same intent uses a key no earlier approval used. Keys and body hashes are the merchant's own
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
                    .map(d -> owner.intent().value() + "/" + d.snapshotHash().substring(0, 12))
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
