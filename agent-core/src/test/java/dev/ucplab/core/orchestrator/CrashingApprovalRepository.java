package dev.ucplab.core.orchestrator;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Wraps an {@link ApprovalRepository} to simulate a process dying around one of its writes (7.3 crash points). Each
 * armed crash fires once, at the next call of that kind: before the write reaches the delegate, or right after it.
 */
final class CrashingApprovalRepository implements ApprovalRepository {

    enum Op { INSERT, TRANSITION }

    private final ApprovalRepository delegate;
    private final Set<Op> crashBefore = EnumSet.noneOf(Op.class);
    private final Set<Op> crashAfter = EnumSet.noneOf(Op.class);

    CrashingApprovalRepository(ApprovalRepository delegate) {
        this.delegate = delegate;
    }

    void crashBeforeNext(Op op) {
        crashBefore.add(op);
    }

    void crashAfterNext(Op op) {
        crashAfter.add(op);
    }

    @Override
    public void insert(ApprovalRecord approval) {
        before(Op.INSERT);
        delegate.insert(approval);
        after(Op.INSERT);
    }

    @Override
    public Optional<ApprovalRecord> findLatest(UserId owner, IntentId intentId) {
        return delegate.findLatest(owner, intentId);
    }

    @Override
    public Optional<ApprovalRecord> findLatestForSystem(IntentId intentId) {
        return delegate.findLatestForSystem(intentId);
    }

    @Override
    public List<ApprovalRecord> listForSystem(IntentId intentId) {
        return delegate.listForSystem(intentId);
    }

    @Override
    public boolean transition(
            String approvalId, ApprovalStatus expected, ApprovalStatus next, Instant at, Optional<UserId> decidedBy) {
        before(Op.TRANSITION);
        boolean moved = delegate.transition(approvalId, expected, next, at, decidedBy);
        after(Op.TRANSITION);
        return moved;
    }

    private void before(Op op) {
        if (crashBefore.remove(op)) {
            throw new InterceptingGateway.SimulatedCrash("before approval " + op);
        }
    }

    private void after(Op op) {
        if (crashAfter.remove(op)) {
            throw new InterceptingGateway.SimulatedCrash("after approval " + op);
        }
    }
}
