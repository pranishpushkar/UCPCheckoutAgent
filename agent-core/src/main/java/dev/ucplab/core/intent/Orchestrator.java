package dev.ucplab.core.intent;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.merchant.MerchantId;
import java.util.List;

/** The purchase orchestrator port (Appendix B [CONTRACT], section 7). Logic arrives in a later M1 step. */
public interface Orchestrator {

    ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines, String rationale);

    /** Load, decide, at most one merchant call, persist (7.3). */
    StepResult advance(IntentId id);

    /** Read only. An intent owned by someone else is reported as not found (T6). */
    IntentStatus status(AuthContext auth, IntentId id);
}
