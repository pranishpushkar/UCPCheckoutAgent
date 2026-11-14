package dev.ucplab.core.intent;

import dev.ucplab.core.identity.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory {@link IntentRepository} with the same rules as the SQL adapter will have: optimistic versioning
 * (7.8), lease fencing (7.6), owner-scoped reads (T2, T6) and an active-dedupe constraint (7.7). Every
 * operation runs under one {@link ReentrantLock} and does no I/O while holding it (7.8).
 *
 * <p>Like the SQL {@code UPDATE}, {@link #update} writes only the mutable columns. The owner, merchant, dedupe key,
 * creation time and lease are never taken from the caller's copy; the lease changes only through
 * {@link #claimRecoverable} and {@link #renewLease}.
 */
public final class InMemoryIntentRepository implements IntentRepository {

    private static final Set<IntentState> RECOVERABLE =
            EnumSet.of(IntentState.QUOTING, IntentState.APPROVED, IntentState.COMPLETING, IntentState.RECONCILING);

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<IntentId, PurchaseIntent> intents = new LinkedHashMap<>();
    private final Map<IntentId, List<IntentEvent>> events = new HashMap<>();
    private final Map<AttemptId, MerchantAttempt> attempts = new LinkedHashMap<>();

    public InMemoryIntentRepository(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<PurchaseIntent> find(UserId owner, IntentId id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        lock.lock();
        try {
            return Optional.ofNullable(intents.get(id)).filter(i -> i.owner().equals(owner));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<PurchaseIntent> findForSystem(IntentId id) {
        Objects.requireNonNull(id, "id");
        lock.lock();
        try {
            return Optional.ofNullable(intents.get(id));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<PurchaseIntent> findActiveByDedupeKey(String dedupeKey) {
        Objects.requireNonNull(dedupeKey, "dedupeKey");
        lock.lock();
        try {
            return intents.values().stream()
                    .filter(i -> i.dedupeKey().equals(dedupeKey) && !i.state().isTerminal())
                    .findFirst();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void insert(PurchaseIntent intent) {
        Objects.requireNonNull(intent, "intent");
        lock.lock();
        try {
            if (intents.containsKey(intent.id())) {
                throw new DuplicateIntentException("intent id already exists: " + intent.id());
            }
            boolean activeConflict = !intent.state().isTerminal() && intents.values().stream()
                    .anyMatch(i -> i.dedupeKey().equals(intent.dedupeKey()) && !i.state().isTerminal());
            if (activeConflict) {
                throw new DuplicateIntentException("an active intent already has this dedupe key");
            }
            intents.put(intent.id(), intent);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void update(PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        Objects.requireNonNull(intent, "intent");
        lock.lock();
        try {
            PurchaseIntent stored = fenced(intent.id(), expectedVersion, leaseOwner);
            requireSameMerchant(stored, intent);
            intents.put(intent.id(), merge(stored, intent, expectedVersion + 1));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void appendEvent(IntentId id, IntentEvent event) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(event, "event");
        lock.lock();
        try {
            requireIntent(id);
            events.computeIfAbsent(id, k -> new ArrayList<>()).add(event);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void recordAttempt(MerchantAttempt attempt, PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(intent, "intent");
        if (!attempt.intentId().equals(intent.id())) {
            throw new IllegalArgumentException("attempt " + attempt.id() + " belongs to another intent");
        }
        lock.lock();
        try {
            // Every check comes before the first write, so a failure leaves nothing behind (7.3 step 3).
            PurchaseIntent stored = fenced(intent.id(), expectedVersion, leaseOwner);
            requireSameMerchant(stored, intent);
            if (attempts.containsKey(attempt.id())) {
                throw new IllegalArgumentException("attempt id already exists: " + attempt.id());
            }
            // The merge holds the I4 check and counts the attempt being recorded, as the SQL adapter does; it comes first.
            PurchaseIntent merged = merge(stored, intent, expectedVersion + 1, Optional.of(attempt));
            attempts.put(attempt.id(), attempt);
            intents.put(intent.id(), merged);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void resolveAttempt(
            AttemptId id, AttemptStatus status, Optional<Integer> httpStatus, Optional<String> responseJson) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(responseJson, "responseJson");
        lock.lock();
        try {
            MerchantAttempt stored = attempts.get(id);
            if (stored == null) {
                throw new IntentNotFoundException("no such attempt: " + id);
            }
            attempts.put(id, stored.resolved(status, httpStatus, responseJson, clock.instant()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void resolveAttempt(
            AttemptId id, AttemptStatus status, Optional<Integer> httpStatus, Optional<String> responseJson,
            PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(responseJson, "responseJson");
        Objects.requireNonNull(intent, "intent");
        lock.lock();
        try {
            // Every check comes before the first write, so a failure leaves nothing behind (7.3 step 5).
            PurchaseIntent stored = fenced(intent.id(), expectedVersion, leaseOwner);
            requireSameMerchant(stored, intent);
            MerchantAttempt attempt = attempts.get(id);
            if (attempt == null) {
                throw new IntentNotFoundException("no such attempt: " + id);
            }
            if (!attempt.intentId().equals(intent.id())) {
                throw new IllegalArgumentException("attempt " + id + " belongs to another intent");
            }
            PurchaseIntent merged = merge(stored, intent, expectedVersion + 1);
            attempts.put(id, attempt.resolved(status, httpStatus, responseJson, clock.instant()));
            intents.put(intent.id(), merged);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void resolveAttempts(
            IntentId intentId, List<AttemptId> ids, AttemptStatus status, Optional<Integer> httpStatus,
            Optional<String> responseJson, long expectedVersion, String leaseOwner) {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(responseJson, "responseJson");
        lock.lock();
        try {
            // Every check comes before the first write, so a failure leaves nothing behind (7.6).
            fenced(intentId, expectedVersion, leaseOwner);
            for (AttemptId id : ids) {
                MerchantAttempt attempt = attempts.get(id);
                if (attempt == null) {
                    throw new IntentNotFoundException("no such attempt: " + id);
                }
                if (!attempt.intentId().equals(intentId)) {
                    throw new IllegalArgumentException("attempt " + id + " belongs to another intent");
                }
            }
            Instant now = clock.instant();
            for (AttemptId id : ids) {
                attempts.put(id, attempts.get(id).resolved(status, httpStatus, responseJson, now));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<PurchaseIntent> claim(IntentId id, String owner, Duration lease) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(lease, "lease");
        lock.lock();
        try {
            PurchaseIntent stored = intents.get(id);
            if (stored == null) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            boolean free = stored.leaseOwner().equals(Optional.of(owner))
                    || stored.leaseUntil().map(t -> t.isBefore(now)).orElse(true);
            if (!free) {
                return Optional.empty();
            }
            PurchaseIntent leased = stored.withLease(Optional.of(owner), Optional.of(now.plus(lease)));
            intents.put(id, leased);
            return Optional.of(leased);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<PurchaseIntent> claimRecoverable(
            String owner, Duration lease, int limit, Set<IntentId> exclude) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(exclude, "exclude");
        Objects.requireNonNull(lease, "lease");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
        lock.lock();
        try {
            Instant now = clock.instant();
            Instant until = now.plus(lease);
            List<PurchaseIntent> candidates = intents.values().stream()
                    .filter(i -> !exclude.contains(i.id()))
                    .filter(i -> RECOVERABLE.contains(i.state()) || i.cancelPending())
                    .filter(i -> i.leaseOwner().equals(Optional.of(owner))
                            || i.leaseUntil().map(t -> t.isBefore(now)).orElse(true))
                    .sorted(Comparator.comparing(PurchaseIntent::createdAt).thenComparing(i -> i.id().value()))
                    .limit(limit)
                    .toList();
            List<PurchaseIntent> claimed = new ArrayList<>(candidates.size());
            for (PurchaseIntent candidate : candidates) {
                PurchaseIntent leased = candidate.withLease(Optional.of(owner), Optional.of(until));
                intents.put(leased.id(), leased);
                claimed.add(leased);
            }
            return List.copyOf(claimed);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean renewLease(IntentId id, String owner, Duration lease) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(lease, "lease");
        lock.lock();
        try {
            PurchaseIntent stored = intents.get(id);
            if (stored == null || !stored.leaseOwner().equals(Optional.of(owner))) {
                return false;
            }
            intents.put(id, stored.withLease(stored.leaseOwner(), Optional.of(clock.instant().plus(lease))));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** System view for {@link InMemoryRecoveryScan}: the intents in {@code state}, oldest first. */
    List<PurchaseIntent> inState(IntentState state) {
        lock.lock();
        try {
            return intents.values().stream().filter(i -> i.state() == state)
                    .sorted(Comparator.comparing(PurchaseIntent::createdAt).thenComparing(i -> i.id().value()))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    /** The owner's intents, in no particular order: the view {@link InMemoryIntentQueries} pages over (T2). */
    List<PurchaseIntent> ownedBy(UserId owner) {
        lock.lock();
        try {
            return intents.values().stream().filter(i -> i.owner().equals(owner)).toList();
        } finally {
            lock.unlock();
        }
    }

    /** Test and harness view of an intent's event log, in append order (sequence numbers are the positions plus one). */
    public List<IntentEvent> events(IntentId id) {
        lock.lock();
        try {
            return List.copyOf(events.getOrDefault(id, List.of()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<MerchantAttempt> attempts(IntentId id) {
        Objects.requireNonNull(id, "id");
        lock.lock();
        try {
            return attempts.values().stream().filter(a -> a.intentId().equals(id)).toList();
        } finally {
            lock.unlock();
        }
    }

    private PurchaseIntent requireIntent(IntentId id) {
        PurchaseIntent stored = intents.get(id);
        if (stored == null) {
            throw new IntentNotFoundException("no such intent: " + id);
        }
        return stored;
    }

    /** The fencing check of 7.6: the lease owner must match first, then the version (7.8). Caller holds the lock. */
    private PurchaseIntent fenced(IntentId id, long expectedVersion, String leaseOwner) {
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        PurchaseIntent stored = requireIntent(id);
        if (!stored.leaseOwner().equals(Optional.of(leaseOwner))) {
            throw new LeaseLostException(id, leaseOwner);
        }
        if (stored.version() != expectedVersion) {
            throw new StaleVersionException(id, expectedVersion, stored.version());
        }
        return stored;
    }

    /** I11: an intent belongs to one merchant for its whole life. Checked before any write, so a refusal leaves nothing. */
    private static void requireSameMerchant(PurchaseIntent stored, PurchaseIntent update) {
        if (!stored.merchantId().equals(update.merchantId())) {
            throw new IllegalArgumentException("I11: the merchant of intent " + stored.id() + " cannot change");
        }
    }

    /** Caller holds the lock. Refuses a session change once a complete attempt exists (I4). */
    private PurchaseIntent merge(PurchaseIntent stored, PurchaseIntent update, long newVersion) {
        return merge(stored, update, newVersion, Optional.empty());
    }

    /** As above, and {@code recording}, an attempt written in the same call, counts as one that exists (I4). */
    private PurchaseIntent merge(PurchaseIntent stored, PurchaseIntent update, long newVersion,
                                 Optional<MerchantAttempt> recording) {
        if (stored.sessionId().isPresent() && !stored.sessionId().equals(update.sessionId())
                && (recording.filter(a -> a.op() == AttemptOp.COMPLETE).isPresent()
                || attempts.values().stream()
                        .anyMatch(a -> a.intentId().equals(stored.id()) && a.op() == AttemptOp.COMPLETE))) {
            throw new IllegalStateException("I4: session of intent " + stored.id()
                    + " cannot change after a complete attempt");
        }
        return new PurchaseIntent(
                stored.id(), stored.owner(), stored.merchantId(), stored.dedupeKey(), stored.lines(),
                stored.buyerEmail(), update.state(), newVersion, update.sessionId(), update.approvedSnapshotHash(),
                update.requoteCount(), update.orderId(), update.failureReason(), update.uncertainSince(),
                update.cancelPending(), stored.leaseOwner(), stored.leaseUntil(), stored.createdAt(), update.updatedAt());
    }
}
