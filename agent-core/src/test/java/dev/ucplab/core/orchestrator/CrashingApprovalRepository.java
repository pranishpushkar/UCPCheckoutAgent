package dev.ucplab.core.orchestrator;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Wraps an {@link ApprovalRepository} to simulate a process dying around one of its calls (7.3 crash points). {@link Op}
 * has one constant per method of the port. A crash is armed for the next call of an operation, or for the n-th
 * ({@link #plan()}), and fires once: before the call reaches the delegate, or right after it.
 */
final class CrashingApprovalRepository implements ApprovalRepository {

    enum Op { INSERT, FIND_LATEST, FIND_LATEST_FOR_SYSTEM, LIST_FOR_SYSTEM, TRANSITION }

    private final ApprovalRepository delegate;
    private final CrashPlan<Op> plan;

    CrashingApprovalRepository(ApprovalRepository delegate) {
        this(delegate, new CrashPlan<>(Op.class));
    }

    CrashingApprovalRepository(ApprovalRepository delegate, CrashPlan<Op> plan) {
        this.delegate = delegate;
        this.plan = plan;
    }

    CrashPlan<Op> plan() {
        return plan;
    }

    void crashBeforeNext(Op op) {
        plan.armNext(op, CrashPlan.Phase.BEFORE);
    }

    void crashAfterNext(Op op) {
        plan.armNext(op, CrashPlan.Phase.AFTER);
    }

    private <T> T call(Op op, Supplier<T> body) {
        plan.before(op);
        T result = body.get();
        plan.after(op);
        return result;
    }

    @Override
    public void insert(ApprovalRecord approval) {
        call(Op.INSERT, () -> {
            delegate.insert(approval);
            return null;
        });
    }

    @Override
    public Optional<ApprovalRecord> findLatest(UserId owner, IntentId intentId) {
        return call(Op.FIND_LATEST, () -> delegate.findLatest(owner, intentId));
    }

    @Override
    public Optional<ApprovalRecord> findLatestForSystem(IntentId intentId) {
        return call(Op.FIND_LATEST_FOR_SYSTEM, () -> delegate.findLatestForSystem(intentId));
    }

    @Override
    public List<ApprovalRecord> listForSystem(IntentId intentId) {
        return call(Op.LIST_FOR_SYSTEM, () -> delegate.listForSystem(intentId));
    }

    @Override
    public boolean transition(
            String approvalId, ApprovalStatus expected, ApprovalStatus next, Instant at, Optional<UserId> decidedBy) {
        return call(Op.TRANSITION, () -> delegate.transition(approvalId, expected, next, at, decidedBy));
    }
}
