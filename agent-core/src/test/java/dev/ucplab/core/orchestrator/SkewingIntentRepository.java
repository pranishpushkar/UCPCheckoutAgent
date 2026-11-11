package dev.ucplab.core.orchestrator;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.AttemptId;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps an {@link IntentRepository} so that a write stores a version other than {@code expectedVersion + 1}: once
 * armed, the next intent write is followed by one more fenced write of the stored intent. It stands for an adapter
 * whose stored version is not the caller's arithmetic (a trigger, or a second statement in the same transaction), so
 * code must use what was stored, not what it expects.
 */
final class SkewingIntentRepository implements IntentRepository {

    private final IntentRepository delegate;
    private final AtomicBoolean armed = new AtomicBoolean();

    SkewingIntentRepository(IntentRepository delegate) {
        this.delegate = delegate;
    }

    /** The next write of an intent (update, recordAttempt or the fenced resolveAttempt) stores one version more. */
    void skewNextWrite() {
        armed.set(true);
    }

    private void skew(IntentId id, String leaseOwner) {
        if (armed.compareAndSet(true, false)) {
            PurchaseIntent stored = delegate.findForSystem(id).orElseThrow();
            delegate.update(stored, stored.version(), leaseOwner);
        }
    }

    @Override
    public void update(PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        delegate.update(intent, expectedVersion, leaseOwner);
        skew(intent.id(), leaseOwner);
    }

    @Override
    public void recordAttempt(MerchantAttempt attempt, PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        delegate.recordAttempt(attempt, intent, expectedVersion, leaseOwner);
        skew(intent.id(), leaseOwner);
    }

    @Override
    public void resolveAttempt(
            AttemptId id, AttemptStatus status, Optional<Integer> httpStatus, Optional<String> responseJson,
            PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        delegate.resolveAttempt(id, status, httpStatus, responseJson, intent, expectedVersion, leaseOwner);
        skew(intent.id(), leaseOwner);
    }

    @Override
    public Optional<PurchaseIntent> find(UserId owner, IntentId id) {
        return delegate.find(owner, id);
    }

    @Override
    public Optional<PurchaseIntent> findForSystem(IntentId id) {
        return delegate.findForSystem(id);
    }

    @Override
    public Optional<PurchaseIntent> findActiveByDedupeKey(String dedupeKey) {
        return delegate.findActiveByDedupeKey(dedupeKey);
    }

    @Override
    public void insert(PurchaseIntent intent) {
        delegate.insert(intent);
    }

    @Override
    public void appendEvent(IntentId id, IntentEvent event) {
        delegate.appendEvent(id, event);
    }

    @Override
    public void resolveAttempt(
            AttemptId id, AttemptStatus status, Optional<Integer> httpStatus, Optional<String> responseJson) {
        delegate.resolveAttempt(id, status, httpStatus, responseJson);
    }

    @Override
    public List<MerchantAttempt> attempts(IntentId id) {
        return delegate.attempts(id);
    }

    @Override
    public void resolveAttempts(
            IntentId intentId, List<AttemptId> ids, AttemptStatus status, Optional<Integer> httpStatus,
            Optional<String> responseJson, long expectedVersion, String leaseOwner) {
        delegate.resolveAttempts(intentId, ids, status, httpStatus, responseJson, expectedVersion, leaseOwner);
    }

    @Override
    public Optional<PurchaseIntent> claim(IntentId id, String owner, Duration lease) {
        return delegate.claim(id, owner, lease);
    }

    @Override
    public List<PurchaseIntent> claimRecoverable(String owner, Duration lease, int limit) {
        return delegate.claimRecoverable(owner, lease, limit);
    }

    @Override
    public boolean renewLease(IntentId id, String owner, Duration lease) {
        return delegate.renewLease(id, owner, lease);
    }
}
