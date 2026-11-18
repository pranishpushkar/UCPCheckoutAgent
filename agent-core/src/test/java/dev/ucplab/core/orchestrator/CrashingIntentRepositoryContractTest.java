package dev.ucplab.core.orchestrator;

import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentRepositoryContract;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;

/**
 * {@link CrashingIntentRepository}, with no crash armed, runs the repository contract it wraps (H3 P4; 7.3, 7.6, 7.8): a
 * decorator that cannot pass the contract with its fault off would make the crash matrix test something else.
 */
@Tag("sequential")
class CrashingIntentRepositoryContractTest extends IntentRepositoryContract {

    private InMemoryIntentRepository memory;

    @Override
    protected IntentRepository create(Clock clock) {
        memory = new InMemoryIntentRepository(clock);
        return new CrashingIntentRepository(memory, new CrashPlan<>(CrashingIntentRepository.Op.class));
    }

    @Override
    protected List<IntentEvent> events(IntentId id) {
        return memory.events(id);
    }
}
