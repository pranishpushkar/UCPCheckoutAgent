package dev.ucplab.core.intent;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.merchant.MerchantId;
import java.util.List;
import java.util.Optional;

/** The purchase orchestrator port (Appendix B [CONTRACT], section 7). Logic arrives in a later M1 step. */
public interface Orchestrator {

    /** Proposes under the run's {@code budget} (9.1 P9), which comes from the run, never from model output. */
    ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines, String rationale,
            Optional<Budget> budget);

    /** A proposal with no budget. */
    default ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines, String rationale) {
        return propose(auth, merchant, lines, rationale, Optional.empty());
    }

    /** Load, decide, at most one merchant call, persist (7.3). */
    StepResult advance(IntentId id);

    /** Read only. An intent owned by someone else is reported as not found (T6). */
    IntentStatus status(AuthContext auth, IntentId id);
}
