package dev.ucplab.core.approval;

class InMemoryApprovalRepositoryTest extends ApprovalRepositoryContract {

    @Override
    protected ApprovalRepository create() {
        return new InMemoryApprovalRepository();
    }

    @Override
    protected void storeIntent(dev.ucplab.core.intent.IntentId id, dev.ucplab.core.identity.UserId owner) {
        // The in-memory adapter keeps no intents.
    }
}
