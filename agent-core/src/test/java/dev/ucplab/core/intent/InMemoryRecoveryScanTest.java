package dev.ucplab.core.intent;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.approval.InMemoryApprovalRepository;

class InMemoryRecoveryScanTest extends RecoveryScanContract {

    @Override
    protected void create(MutableClock clock) {
        InMemoryIntentRepository memory = new InMemoryIntentRepository(clock);
        InMemoryApprovalRepository memoryApprovals = new InMemoryApprovalRepository();
        intents = memory;
        approvals = memoryApprovals;
        scan = new InMemoryRecoveryScan(memory, memoryApprovals);
    }
}
