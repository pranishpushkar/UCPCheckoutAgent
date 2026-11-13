package dev.ucplab.eval.check;

import dev.ucplab.eval.RunEvidence;
import java.util.List;

/**
 * One invariant of 4.3 checked over one run's evidence (m4-plan section 5). Each check is computed from ground truth the
 * agent does not control: O (the merchant's orders), G (the merchant's own view of each session), L (the proxy log in
 * front of the merchant) and A (the auto-approver's own decisions). D, what the agent persisted, is read only where
 * the invariant is about that stored state, and {@link #evidence()} says where.
 *
 * <p>A run that was aborted fails every check: none of them could be established.
 */
public sealed interface InvariantCheck
        permits I1OneOrder, I2OrderMatchesApproval, I3CompleteNeedsApproval, I4SessionFixed, I5Liveness,
        I7SameKeySameBody {

    /** The 4.3 id, for example {@code I1}. */
    String id();

    /** What the check establishes, in one sentence. */
    String description();

    /** The evidence it reads, and what it uses D for, if anything. */
    String evidence();

    /** Every violation in the run; empty when the run is clean. */
    List<Violation> check(RunEvidence evidence, CheckContext context);
}
