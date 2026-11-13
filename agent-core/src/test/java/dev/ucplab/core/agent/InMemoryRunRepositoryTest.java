package dev.ucplab.core.agent;

class InMemoryRunRepositoryTest extends RunRepositoryContract {

    @Override
    protected RunRepository create() {
        return new InMemoryRunRepository();
    }
}
