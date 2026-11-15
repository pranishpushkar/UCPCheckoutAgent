package dev.ucplab.core.intent;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.approval.InMemoryApprovalRepository;

class InMemoryInFlightMerchantsTest extends InFlightMerchantsContract {

    @Override
    protected void create(MutableClock clock) {
        InMemoryIntentRepository memory = new InMemoryIntentRepository(clock);
        intents = memory;
        inFlight = new InMemoryRecoveryScan(memory, new InMemoryApprovalRepository());
    }
}
