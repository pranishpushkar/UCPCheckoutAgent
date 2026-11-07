package dev.ucplab.core.approval;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.CheckoutView;

/**
 * The only code that can move an intent to APPROVED (9.2). Appendix B [CONTRACT].
 */
public interface ApprovalService {

    /** Builds the snapshot from the merchant's quote, never from model output (9.2). */
    PendingApproval open(PurchaseIntent intent, CheckoutView quote);

    /** Owner and recent sign-in only (I10, T3, T4). */
    ApprovalDecision decide(AuthContext auth, IntentId id, String token, boolean approve);
}
