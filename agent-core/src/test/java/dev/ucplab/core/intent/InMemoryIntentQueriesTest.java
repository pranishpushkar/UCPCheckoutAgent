package dev.ucplab.core.intent;

import java.time.Clock;

class InMemoryIntentQueriesTest extends IntentQueriesContract {

    @Override
    protected Stores create(Clock clock) {
        InMemoryIntentRepository memory = new InMemoryIntentRepository(clock);
        return new Stores(memory, new InMemoryIntentQueries(memory));
    }
}
