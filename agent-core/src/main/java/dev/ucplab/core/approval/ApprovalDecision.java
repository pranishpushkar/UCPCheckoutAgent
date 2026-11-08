package dev.ucplab.core.approval;

import dev.ucplab.core.intent.IntentId;
import java.util.Objects;

/**
 * Outcome of {@link ApprovalService#decide}. The refusals follow the API errors in 23.2: {@code reauth_required}
 * (T4), {@code approval_not_pending} (already decided, expired or consumed) and not found (T6, which also covers a
 * caller who is not the owner). An invalid token changes nothing.
 */
public sealed interface ApprovalDecision {

    record Approved(IntentId intentId) implements ApprovalDecision {
        public Approved {
            Objects.requireNonNull(intentId, "intentId");
        }
    }

    record Rejected(IntentId intentId) implements ApprovalDecision {
        public Rejected {
            Objects.requireNonNull(intentId, "intentId");
        }
    }

    record ReauthRequired() implements ApprovalDecision {}

    record NotPending() implements ApprovalDecision {}

    record NotFound() implements ApprovalDecision {}

    record InvalidToken() implements ApprovalDecision {}
}
