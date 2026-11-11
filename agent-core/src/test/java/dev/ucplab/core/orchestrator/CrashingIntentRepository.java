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
import java.util.function.Supplier;

/**
 * Wraps an {@link IntentRepository} to simulate a process dying around any one of its calls. {@link Op} has one
 * constant per method of the port (the reach test of the crash matrix checks that with reflection). Works over any
 * adapter, so the same matrix runs against the SQL one.
 */
final class CrashingIntentRepository implements IntentRepository {

    enum Op {
        FIND, FIND_FOR_SYSTEM, FIND_ACTIVE_BY_DEDUPE_KEY, INSERT, UPDATE, APPEND_EVENT, RECORD_ATTEMPT,
        RESOLVE_ATTEMPT_UNFENCED, ATTEMPTS, RESOLVE_ATTEMPT, RESOLVE_ATTEMPTS, CLAIM, CLAIM_RECOVERABLE, RENEW_LEASE
    }

    private final IntentRepository delegate;
    private final CrashPlan<Op> plan;

    CrashingIntentRepository(IntentRepository delegate, CrashPlan<Op> plan) {
        this.delegate = delegate;
        this.plan = plan;
    }

    private <T> T call(Op op, Supplier<T> body) {
        plan.before(op);
        T result = body.get();
        plan.after(op);
        return result;
    }

    private void run(Op op, Runnable body) {
        call(op, () -> {
            body.run();
            return null;
        });
    }

    @Override
    public Optional<PurchaseIntent> find(UserId owner, IntentId id) {
        return call(Op.FIND, () -> delegate.find(owner, id));
    }

    @Override
    public Optional<PurchaseIntent> findForSystem(IntentId id) {
        return call(Op.FIND_FOR_SYSTEM, () -> delegate.findForSystem(id));
    }

    @Override
    public Optional<PurchaseIntent> findActiveByDedupeKey(String dedupeKey) {
        return call(Op.FIND_ACTIVE_BY_DEDUPE_KEY, () -> delegate.findActiveByDedupeKey(dedupeKey));
    }

    @Override
    public void insert(PurchaseIntent intent) {
        run(Op.INSERT, () -> delegate.insert(intent));
    }

    @Override
    public void update(PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        run(Op.UPDATE, () -> delegate.update(intent, expectedVersion, leaseOwner));
    }

    @Override
    public void appendEvent(IntentId id, IntentEvent event) {
        run(Op.APPEND_EVENT, () -> delegate.appendEvent(id, event));
    }

    @Override
    public void recordAttempt(MerchantAttempt attempt, PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        run(Op.RECORD_ATTEMPT, () -> delegate.recordAttempt(attempt, intent, expectedVersion, leaseOwner));
    }

    @Override
    public void resolveAttempt(
            AttemptId id, AttemptStatus status, Optional<Integer> httpStatus, Optional<String> responseJson) {
        run(Op.RESOLVE_ATTEMPT_UNFENCED, () -> delegate.resolveAttempt(id, status, httpStatus, responseJson));
    }

    @Override
    public List<MerchantAttempt> attempts(IntentId id) {
        return call(Op.ATTEMPTS, () -> delegate.attempts(id));
    }

    @Override
    public void resolveAttempt(
            AttemptId id, AttemptStatus status, Optional<Integer> httpStatus, Optional<String> responseJson,
            PurchaseIntent intent, long expectedVersion, String leaseOwner) {
        run(Op.RESOLVE_ATTEMPT, () -> delegate.resolveAttempt(
                id, status, httpStatus, responseJson, intent, expectedVersion, leaseOwner));
    }

    @Override
    public void resolveAttempts(
            IntentId intentId, List<AttemptId> ids, AttemptStatus status, Optional<Integer> httpStatus,
            Optional<String> responseJson, long expectedVersion, String leaseOwner) {
        run(Op.RESOLVE_ATTEMPTS, () -> delegate.resolveAttempts(
                intentId, ids, status, httpStatus, responseJson, expectedVersion, leaseOwner));
    }

    @Override
    public Optional<PurchaseIntent> claim(IntentId id, String owner, Duration lease) {
        return call(Op.CLAIM, () -> delegate.claim(id, owner, lease));
    }

    @Override
    public List<PurchaseIntent> claimRecoverable(String owner, Duration lease, int limit) {
        return call(Op.CLAIM_RECOVERABLE, () -> delegate.claimRecoverable(owner, lease, limit));
    }

    @Override
    public boolean renewLease(IntentId id, String owner, Duration lease) {
        return call(Op.RENEW_LEASE, () -> delegate.renewLease(id, owner, lease));
    }
}
