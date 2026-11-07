package dev.ucplab.core.policy;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.MerchantId;
import java.util.List;

/**
 * Policy port (Appendix B [CONTRACT], table 9.1, FR7). Guards PROPOSED -> QUOTING and
 * QUOTING -> AWAITING_APPROVAL in table 7.2. Re-quote rules P7 and P8 are in {@link RequotePolicy}
 * because the orchestrator applies them without the model (9.1).
 */
public interface PolicyEngine {

    /** P1, P4 and P5 for a proposal from the agent. */
    PolicyDecision evaluateProposal(UserId user, MerchantId merchant, List<ProposedLine> lines);

    /** P2, P3 and P4 for the merchant's quote (the authoritative items and total). */
    PolicyDecision evaluateQuote(UserId user, CheckoutView quote);
}
