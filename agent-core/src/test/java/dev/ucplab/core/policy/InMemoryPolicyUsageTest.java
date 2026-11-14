package dev.ucplab.core.policy;

import dev.ucplab.core.approval.InMemoryApprovalRepository;
import dev.ucplab.core.intent.InMemoryIntentRepository;
import java.time.Clock;

/** The in-memory {@link PolicyUsage} runs the usage contract (H3 P3; 9.1 P3 and P5, T5, section 20). */
class InMemoryPolicyUsageTest extends PolicyUsageContract {

    @Override
    protected Stores create(Clock clock) {
        InMemoryIntentRepository intents = new InMemoryIntentRepository(clock);
        InMemoryApprovalRepository approvals = new InMemoryApprovalRepository();
        return new Stores(intents, approvals, new InMemoryPolicyUsage(intents, approvals));
    }
}
