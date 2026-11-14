package dev.ucplab.core.intent;

import dev.ucplab.core.identity.UserId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
     * <p>I11: an intent belongs to exactly one merchant for its whole life. A copy whose {@code merchantId} differs from
     * the stored one is refused, here and in {@link #recordAttempt} and {@link #resolveAttempt}, before anything is
     * written.
     *
     * @throws StaleVersionException the stored version differs
     * @throws LeaseLostException the stored lease owner differs
     * @throws IllegalArgumentException (message starting {@code "I11:"}) the copy names another merchant
     */
    void update(PurchaseIntent intent, long expectedVersion, String leaseOwner);

    void appendEvent(IntentId id, IntentEvent event);

    /**
     * Inserts the attempt and updates the intent as one atomic step, under the same checks as
     * {@link #update} (7.3 step 3). When it throws, neither change is visible.
     */
    void recordAttempt(MerchantAttempt attempt, PurchaseIntent intent, long expectedVersion, String leaseOwner);

    void resolveAttempt(AttemptId id, AttemptStatus status, Optional<Integer> httpStatus, Optional<String> responseJson);

    // The three methods below are additions to the Appendix B port. 7.3 needs them and the listed methods cannot
    // express them: step 1 loads the unresolved attempt, resends read the stored body (7.4), and step 5 must move
    // the state together with the attempt under the same fencing as step 3 (7.6). claim takes the lease on one named
    // intent, which propose, ApprovalService.decide and advance need for states RecoveryWorker does not claim.

    /** System read (T2): the intent's attempts in the order they were recorded (7.3 step 1, 7.4). */
    List<MerchantAttempt> attempts(IntentId id);

    /**
     * 7.3 step 5 as one atomic step: resolves the attempt and writes the intent under the same checks as
     * {@link #update}. When it throws, neither change is visible, so the attempt stays SENT and is later treated as
     * uncertain (7.3).
     *
     * @throws StaleVersionException the stored version differs
     * @throws LeaseLostException the stored lease owner differs
     */
    void resolveAttempt(
            AttemptId id, AttemptStatus status, Optional<Integer> httpStatus, Optional<String> responseJson,
            PurchaseIntent intent, long expectedVersion, String leaseOwner);

    /**
     * Settles several attempts of one intent at once, under the same fencing as {@link #update} (7.6): the stored lease
     * owner must be {@code leaseOwner}, then the stored version must be {@code expectedVersion}. It writes only the
     * attempts: the intent and its version are unchanged, so the caller's own fenced write that follows still carries
     * {@code expectedVersion}. All or none: when it throws, no attempt has changed.
     *
     * @throws LeaseLostException the stored lease owner differs
     * @throws StaleVersionException the stored version differs
     * @throws IntentNotFoundException the intent or one of the attempts does not exist
     * @throws IllegalArgumentException an attempt belongs to another intent
     */
    void resolveAttempts(
            IntentId intentId, List<AttemptId> ids, AttemptStatus status, Optional<Integer> httpStatus,
            Optional<String> responseJson, long expectedVersion, String leaseOwner);

    /**
     * Takes or renews the lease on one intent when it is free, expired or already held by {@code owner}, setting it
     * to now + {@code lease} (7.6). Empty when another worker holds a live lease or the intent does not exist.
     */
    Optional<PurchaseIntent> claim(IntentId id, String owner, Duration lease);

    /**
     * Claims up to {@code limit} intents that need a worker whose lease is empty, expired or already held by
     * {@code owner}, setting the lease owner and expiry to now + {@code lease} (7.6). Taking back its own live lease is
     * how a worker that parked an intent for a long wait (7.5) renews it on the next pass. The intents are those in QUOTING,
     * APPROVED, COMPLETING or RECONCILING, and any intent with {@code cancel_pending} set. AWAITING_APPROVAL intents with an expired approval are found by {@link RecoveryScan}.
     */
    default List<PurchaseIntent> claimRecoverable(String owner, Duration lease, int limit) {
        return claimRecoverable(owner, lease, limit, Set.of());
    }

    /**
     * {@link #claimRecoverable(String, Duration, int)} leaving out the intents in {@code exclude}: they are neither
     * claimed nor renewed nor counted against {@code limit}. A worker passes the intents it has parked until a later
     * time (7.5), so intents that are not due cannot fill the limit and keep others from being picked up.
     */
    List<PurchaseIntent> claimRecoverable(String owner, Duration lease, int limit, Set<IntentId> exclude);

    /** False means the lease is lost and the caller must stop working on the intent (7.6). */
    boolean renewLease(IntentId id, String owner, Duration lease);
}
