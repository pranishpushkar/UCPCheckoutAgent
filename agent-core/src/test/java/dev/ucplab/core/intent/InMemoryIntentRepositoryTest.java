package dev.ucplab.core.intent;

import java.time.Clock;
import java.util.List;

class InMemoryIntentRepositoryTest extends IntentRepositoryContract {

    private InMemoryIntentRepository memory;

    @Override
    protected IntentRepository create(Clock clock) {
        memory = new InMemoryIntentRepository(clock);
        return memory;
    }

    @Override
    protected List<IntentEvent> events(IntentId id) {
        return memory.events(id);
    }
}
