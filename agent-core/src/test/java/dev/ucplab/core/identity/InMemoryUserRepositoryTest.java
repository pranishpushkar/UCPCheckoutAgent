package dev.ucplab.core.identity;

class InMemoryUserRepositoryTest extends UserRepositoryContract {

    @Override
    protected UserRepository create() {
        return new InMemoryUserRepository();
    }
}
