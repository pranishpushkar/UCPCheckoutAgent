package dev.ucplab.core.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/** In-memory {@link SessionRepository}, keyed by id hash. One {@link ReentrantLock}, no I/O while holding it (7.8). */
public final class InMemorySessionRepository implements SessionRepository {

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, UserSession> sessions = new HashMap<>();

    public InMemorySessionRepository(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void insert(UserSession session) {
        Objects.requireNonNull(session, "session");
        lock.lock();
        try {
            if (sessions.putIfAbsent(session.idHash(), session) != null) {
                throw new IllegalArgumentException("a session with this id already exists");
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<UserSession> findLive(String rawId) {
        String hash = UserSession.hashOf(rawId);
        Instant now = clock.instant();
        lock.lock();
        try {
            return Optional.ofNullable(sessions.get(hash)).filter(s -> s.isLive(now));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean touch(String rawId) {
        String hash = UserSession.hashOf(rawId);
        Instant now = clock.instant();
        lock.lock();
        try {
            UserSession stored = sessions.get(hash);
            if (stored == null || !stored.isLive(now)) {
                return false;
            }
            sessions.put(hash, stored.seenAt(now));
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean delete(String rawId) {
        String hash = UserSession.hashOf(rawId);
        lock.lock();
        try {
            return sessions.remove(hash) != null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int deleteAllFor(UserId user) {
        Objects.requireNonNull(user, "user");
        lock.lock();
        try {
            int before = sessions.size();
            sessions.values().removeIf(s -> s.user().equals(user));
            return before - sessions.size();
        } finally {
            lock.unlock();
        }
    }
}
