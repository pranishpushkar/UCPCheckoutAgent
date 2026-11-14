package dev.ucplab.core.agent;

class InMemoryRunRepositoryTest extends RunRepositoryContract {

    @Override
    protected RunRepository create() {
        return new InMemoryRunRepository(new dev.ucplab.core.MutableClock(java.time.Instant.parse("2026-01-01T00:01:00Z")));
    }
}
