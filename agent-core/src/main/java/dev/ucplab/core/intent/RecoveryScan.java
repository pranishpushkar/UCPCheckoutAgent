package dev.ucplab.core.intent;

import java.time.Instant;
import java.util.List;

/**
 * System read for RecoveryWorker (7.6, Appendix B): intents the worker must advance although
 * {@link IntentRepository#claimRecoverable} does not return them. It takes no lease and changes nothing; the worker's
 * {@code advance} claims the intent.
 */
public interface RecoveryScan {

    /**
     * Intents still in AWAITING_APPROVAL whose newest approval has expired: PENDING with {@code expires_at} at or before
     * {@code now}, or already EXPIRED (7.6 "What the worker picks up"). An intent in any other state is never returned,
     * even when an old approval row is still PENDING, and neither is an intent whose lease another worker holds.
     * Oldest expiry first, at most {@code limit}.
     *
     * @throws IllegalArgumentException {@code limit} is below 1
     */
    List<IntentId> approvalsDue(Instant now, int limit);
}
