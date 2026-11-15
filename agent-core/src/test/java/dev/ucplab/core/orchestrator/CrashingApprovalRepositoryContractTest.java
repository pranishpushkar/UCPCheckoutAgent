package dev.ucplab.core.orchestrator;

import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.approval.ApprovalRepositoryContract;
import dev.ucplab.core.approval.InMemoryApprovalRepository;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;

/** {@link CrashingApprovalRepository}, with no crash armed, runs the repository contract it wraps (H3 P4; 7.3, 9.2, I10). */
class CrashingApprovalRepositoryContractTest extends ApprovalRepositoryContract {

    @Override
    protected ApprovalRepository create() {
        return new CrashingApprovalRepository(new InMemoryApprovalRepository());
    }

    @Override
    protected void storeIntent(IntentId id, UserId owner) {
        // The in-memory adapter keeps no intents.
    }
}
