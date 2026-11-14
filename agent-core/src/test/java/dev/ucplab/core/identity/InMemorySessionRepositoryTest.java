package dev.ucplab.core.identity;

import java.time.Clock;

class InMemorySessionRepositoryTest extends SessionRepositoryContract {

    @Override
    protected SessionRepository create(Clock clock) {
        return new InMemorySessionRepository(clock);
    }
}
