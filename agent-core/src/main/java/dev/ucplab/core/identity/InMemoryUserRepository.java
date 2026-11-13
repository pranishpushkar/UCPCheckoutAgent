package dev.ucplab.core.identity;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link UserRepository}. No lock: each sign-in is one atomic {@code compute} (7.8). */
public final class InMemoryUserRepository implements UserRepository {

    private final Map<UserId, AppUser> users = new ConcurrentHashMap<>();

    @Override
    public AppUser recordLogin(UserId id, String email, Instant at) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(at, "at");
        return users.compute(id, (k, old) -> new AppUser(id, email, old == null ? at : old.createdAt(), at));
    }

    @Override
    public Optional<AppUser> find(UserId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(users.get(id));
    }
}
