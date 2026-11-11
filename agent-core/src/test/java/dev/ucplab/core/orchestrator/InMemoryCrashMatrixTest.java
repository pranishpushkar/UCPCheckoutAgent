package dev.ucplab.core.orchestrator;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.approval.InMemoryApprovalRepository;
import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.InMemoryRecoveryScan;

/** The crash matrix over the in-memory adapters. */
class InMemoryCrashMatrixTest extends CrashMatrixContract {

    @Override
    CrashHarness.Stores newStores(MutableClock clock) {
        InMemoryIntentRepository intents = new InMemoryIntentRepository(clock);
        InMemoryApprovalRepository approvals = new InMemoryApprovalRepository();
        return new CrashHarness.Stores(intents, approvals, new InMemoryRecoveryScan(intents, approvals));
    }
}
