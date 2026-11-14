package dev.ucplab.core.orchestrator;

import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentRepositoryContract;
import java.time.Clock;
import java.util.List;

/** {@link SkewingIntentRepository}, not armed, runs the repository contract it wraps (H3 P4; 7.3, 7.6, 7.8). */
class SkewingIntentRepositoryContractTest extends IntentRepositoryContract {

    private InMemoryIntentRepository memory;

    @Override
    protected IntentRepository create(Clock clock) {
        memory = new InMemoryIntentRepository(clock);
        return new SkewingIntentRepository(memory);
    }

    @Override
    protected List<IntentEvent> events(IntentId id) {
        return memory.events(id);
    }
}
