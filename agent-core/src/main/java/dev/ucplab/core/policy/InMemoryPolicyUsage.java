package dev.ucplab.core.policy;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.InMemoryApprovalRepository;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link PolicyUsage} over the in-memory intent and approval repositories, with the SQL adapter's semantics (9.1 P3 and
 * P5): live on every call (section 20), keyed by the user (T5), windows {@code [from, to)} on the intent's creation time.
 * P3 sums the total of each counted intent's newest approval, so a re-quoted intent counts once; the total is the quote's,
 * shipping included. P5 counts every intent the user created in the window.
 */
public final class InMemoryPolicyUsage implements PolicyUsage {

    /** The 9.1 P3 states: spend that is committed or may have happened. PROPOSED and QUOTING (a re-quote) are not. */
    private static final List<IntentState> SPEND_STATES = List.of(IntentState.AWAITING_APPROVAL, IntentState.APPROVED,
            IntentState.COMPLETING, IntentState.RECONCILING, IntentState.COMPLETED, IntentState.ESCALATED);

    private final InMemoryIntentRepository intents;
    private final InMemoryApprovalRepository approvals;

    public InMemoryPolicyUsage(InMemoryIntentRepository intents, InMemoryApprovalRepository approvals) {
        this.intents = Objects.requireNonNull(intents, "intents");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
    }

    @Override
    public long committedSpendMinor(UserId user, Instant from, Instant to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        long total = 0;
        for (PurchaseIntent intent : intents.ownedBy(user)) {
            if (SPEND_STATES.contains(intent.state()) && inWindow(intent, from, to)) {
                Optional<ApprovalRecord> newest = approvals.findLatestForSystem(intent.id());
                total = Math.addExact(total, newest.map(a -> a.snapshot().totalMinor()).orElse(0L));
            }
        }
        return total;
    }

    @Override
    public int proposalCount(UserId user, Instant from, Instant to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return (int) intents.ownedBy(user).stream().filter(i -> inWindow(i, from, to)).count();
    }

    private static boolean inWindow(PurchaseIntent intent, Instant from, Instant to) {
        return !intent.createdAt().isBefore(from) && intent.createdAt().isBefore(to);
    }
}
