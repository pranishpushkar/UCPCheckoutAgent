package dev.ucplab.core.intent;

import dev.ucplab.core.identity.UserId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Storage port for intents, events and merchant attempts (Appendix B [CONTRACT], sections 7.3, 7.6, 7.8, 18.1).
 * Every method that reads on behalf of a user takes the owner's {@link UserId} (T2). The methods without
 * one are system methods for RecoveryWorker and the eval harness.
 */
public interface IntentRepository {

    /** Empty when the intent does not exist or belongs to someone else, so existence is not revealed (T6). */
    Optional<PurchaseIntent> find(UserId owner, IntentId id);

    /** RecoveryWorker and eval harness only. */
    Optional<PurchaseIntent> findForSystem(IntentId id);

    Optional<PurchaseIntent> findActiveByDedupeKey(String dedupeKey);

    /** @throws DuplicateIntentException on an active dedupe conflict or a repeated id */
    void insert(PurchaseIntent intent);

    /**
     * Writes the intent's mutable fields and sets the stored version to {@code expectedVersion + 1}, only if the
     * stored version equals {@code expectedVersion} and the stored lease owner is {@code leaseOwner} (7.6).
     *
     * @throws StaleVersionException the stored version differs
     * @throws LeaseLostException the stored lease owner differs
     */
    void update(PurchaseIntent intent, long expectedVersion, String leaseOwner);

    void appendEvent(IntentId id, IntentEvent event);

    /**
     * Inserts the attempt and updates the intent as one atomic step, under the same checks as
     * {@link #update} (7.3 step 3). When it throws, neither change is visible.
     */
    void recordAttempt(MerchantAttempt attempt, PurchaseIntent intent, long expectedVersion, String leaseOwner);

    void resolveAttempt(AttemptId id, AttemptStatus status, Optional<Integer> httpStatus, Optional<String> responseJson);

    /**
     * Claims up to {@code limit} intents in QUOTING, COMPLETING or RECONCILING whose lease is empty or expired,
     * setting the lease owner and expiry to now + {@code lease} (7.6).
     */
    List<PurchaseIntent> claimRecoverable(String owner, Duration lease, int limit);

    /** False means the lease is lost and the caller must stop working on the intent (7.6). */
    boolean renewLease(IntentId id, String owner, Duration lease);
}
