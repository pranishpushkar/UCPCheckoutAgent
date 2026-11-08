package dev.ucplab.core.approval;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storage port for approvals (section 10, 9.2). Appendix B does not list it; it follows the same tenancy rule as
 * {@link dev.ucplab.core.intent.IntentRepository}: user-facing reads take the owner's {@link UserId} (T2) and return
 * empty for anyone else (T6). The {@code ForSystem} reads serve the orchestrator's step function, RecoveryWorker and
 * the eval harness. Never cached (section 20).
 */
public interface ApprovalRepository {

    void insert(ApprovalRecord approval);

    /** The newest approval of the intent, if the intent's approvals belong to {@code owner} (T6). */
    Optional<ApprovalRecord> findLatest(UserId owner, IntentId intentId);

    /** System read: the newest approval of the intent. */
    Optional<ApprovalRecord> findLatestForSystem(IntentId intentId);

    /** System read: every approval of the intent, oldest first (S16 counts them). */
    List<ApprovalRecord> listForSystem(IntentId intentId);

    /**
     * Moves the approval from {@code expected} to {@code next} only if its status is still {@code expected}
     * (compare-and-set, so an approval is decided at most once, 9.2).
     *
     * @return false if the status was not {@code expected}
     */
    boolean transition(String approvalId, ApprovalStatus expected, ApprovalStatus next, Instant at,
            Optional<UserId> decidedBy);
}
