package dev.ucplab.core.agent;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/** In-memory {@link RunRepository}. One {@link ReentrantLock}, no I/O while holding it (7.8). */
public final class InMemoryRunRepository implements RunRepository {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<RunId, RunRecord> runs = new LinkedHashMap<>();

    @Override
    public void insert(UserId owner, RunRecord run) {
        checkOwner(owner, run);
        lock.lock();
        try {
            if (runs.containsKey(run.id())) {
                throw new IllegalArgumentException("run id already exists: " + run.id());
            }
            runs.put(run.id(), run);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<RunRecord> find(UserId owner, RunId id) {
        Objects.requireNonNull(owner, "owner");
        return findForSystem(id).filter(r -> r.owner().equals(owner));
    }

    @Override
    public boolean update(UserId owner, RunRecord run) {
        checkOwner(owner, run);
        lock.lock();
        try {
            RunRecord stored = runs.get(run.id());
            if (stored == null || !stored.owner().equals(owner)) {
                return false;
            }
            runs.put(run.id(), run);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<RunRecord> forIntent(UserId owner, IntentId intent) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(intent, "intent");
        lock.lock();
        try {
            return runs.values().stream()
                    .filter(r -> r.owner().equals(owner) && r.intentIds().contains(intent))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<RunRecord> findForSystem(RunId id) {
        Objects.requireNonNull(id, "id");
        lock.lock();
        try {
            return Optional.ofNullable(runs.get(id));
        } finally {
            lock.unlock();
        }
    }

    private static void checkOwner(UserId owner, RunRecord run) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(run, "run");
        if (!run.owner().equals(owner)) {
            throw new IllegalArgumentException("the run's owner is not the caller");
        }
    }
}
