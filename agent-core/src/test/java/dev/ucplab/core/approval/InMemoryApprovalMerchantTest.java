package dev.ucplab.core.approval;

import dev.ucplab.core.intent.InMemoryIntentRepository;
import java.time.Clock;

class InMemoryApprovalMerchantTest extends ApprovalMerchantContract {

    @Override
    protected void create(Clock clock) {
        intents = new InMemoryIntentRepository(clock);
        approvals = new InMemoryApprovalRepository();
    }
}
