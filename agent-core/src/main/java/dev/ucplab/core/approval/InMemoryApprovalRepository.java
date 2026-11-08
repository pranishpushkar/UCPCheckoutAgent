package dev.ucplab.core.approval;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/** In-memory {@link ApprovalRepository}. One {@link ReentrantLock}, no I/O while holding it (7.8). */
public final class InMemoryApprovalRepository implements ApprovalRepository {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, ApprovalRecord> approvals = new LinkedHashMap<>();

    @Override
    public void insert(ApprovalRecord approval) {
        Objects.requireNonNull(approval, "approval");
        lock.lock();
        try {
            if (approvals.containsKey(approval.id())) {
                throw new IllegalArgumentException("approval id already exists: " + approval.id());
            }
            approvals.put(approval.id(), approval);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<ApprovalRecord> findLatest(UserId owner, IntentId intentId) {
        Objects.requireNonNull(owner, "owner");
        return findLatestForSystem(intentId).filter(a -> a.owner().equals(owner));
    }

    @Override
    public Optional<ApprovalRecord> findLatestForSystem(IntentId intentId) {
        List<ApprovalRecord> all = listForSystem(intentId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public List<ApprovalRecord> listForSystem(IntentId intentId) {
        Objects.requireNonNull(intentId, "intentId");
        lock.lock();
        try {
            return approvals.values().stream().filter(a -> a.intentId().equals(intentId)).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean transition(
            String approvalId, ApprovalStatus expected, ApprovalStatus next, Instant at, Optional<UserId> decidedBy) {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(decidedBy, "decidedBy");
        lock.lock();
        try {
            ApprovalRecord stored = approvals.get(approvalId);
            if (stored == null || stored.status() != expected) {
                return false;
            }
            Optional<Instant> decidedAt = decidedBy.isPresent() ? Optional.of(at) : stored.decidedAt();
            Optional<UserId> by = decidedBy.isPresent() ? decidedBy : stored.decidedBy();
            approvals.put(approvalId, stored.withStatus(next, decidedAt, by));
            return true;
        } finally {
            lock.unlock();
        }
    }
}
