package dev.ucplab.core.policy;

import dev.ucplab.core.identity.UserId;
import java.time.Instant;

/**
 * Port: the per-user counters behind P3 and P5 (9.1), keyed by the authenticated user (T5).
 * Read live on every evaluation, never cached (section 20). Windows are {@code [from, to)}.
 */
// TODO(M3): the SQL adapter checks and updates these counters in the same transaction as the
// intent insert (21.3), so two concurrent proposals cannot both pass a cap.
public interface PolicyUsage {

    /**
     * P3. Total in minor units of the user's intents created in the window whose spend is committed
     * or may have happened: AWAITING_APPROVAL, APPROVED, COMPLETING, RECONCILING, COMPLETED or
     * ESCALATED. An intent in PROPOSED or QUOTING, which includes the one being evaluated, is not
     * counted, so a re-quote is not counted twice.
     */
    long committedSpendMinor(UserId user, Instant from, Instant to);

    /** P5. Number of proposals the user made in the window. */
    int proposalCount(UserId user, Instant from, Instant to);
}
