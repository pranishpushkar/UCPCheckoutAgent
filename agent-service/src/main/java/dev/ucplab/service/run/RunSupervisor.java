package dev.ucplab.service.run;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.agent.RunRepository;
import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.runtime.AgentRuntime;
import dev.ucplab.runtime.RunOutcome;
import dev.ucplab.runtime.RunSession;
import dev.ucplab.runtime.RunTrace;
import dev.ucplab.runtime.UserChannel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Runs the agent for the HTTP API and holds the conversations (23.1, m6-plan section 3.3). A run goes on a virtual
 * thread; the request that started it, or answered it, waits up to {@code runResponseWait} for a settle point: the run
 * asks the user something, or ends. If it has not settled by then the view says {@code running} and the client polls.
 *
 * <p>A live run's {@link Phase} moves only by compare-and-set, and a run has no lock: whoever wins the CAS goes on, the
 * rest are refused (409), so 50 concurrent answers give exactly one winner. An answer to {@code ask_user} continues
 * the run inside the runtime, under the same {@code BudgetMeter}. A message to a run that settled in {@code done} or
 * {@code waiting_for_approval} is a continuation: it starts a fresh budget through {@link AgentRuntime#resume}, adds a
 * turn, and is limited by P5 and by the 6-turn cap. Everything else is {@link RunRefused.Kind#NOT_WAITING}.
 *
 * <p>One active run per user is checked here, against the live runs of this instance, never against stored records: a
 * record a restart orphaned must not block its user. Such a record, with no outcome and untouched for
 * {@code orphanAfter}, ends as {@code failed}/{@link #INTERRUPTED} when it is next read, through the owner's own
 * {@code update}.
 *
 * <p>Nothing here takes a monitor or a lock, or holds anything across a model call, a merchant call or a wait.
 */
public final class RunSupervisor implements AutoCloseable {

    /** The reason for a run cut off by shutdown or orphaned by a restart (23.1); only the service writes it. */
    public static final String INTERRUPTED = "interrupted";
    private static final String INTERNAL_ERROR = "internal_error";

    /** Where a live run is. */
    private sealed interface Phase permits Running, Waiting, Settled {
    }

    private record Running() implements Phase {
    }

    private record Waiting(String question, Instant askedAt, CompletableFuture<String> answer) implements Phase {
    }

    private record Settled(String status, Optional<String> reason) implements Phase {
        boolean resumable() {
            return status.equals(RunView.DONE) || status.equals(RunView.WAITING_FOR_APPROVAL);
        }
    }

    private static final Running RUNNING = new Running();

    private final AgentRuntime runtime;
    private final RunRepository runs;
    private final IntentRepository intents;
    private final Clock clock;
    private final IdGenerator ids;
    private final RunSupervisorConfig config;
    private final AnswerWait answerWait;
    private final ConcurrentHashMap<RunId, LiveRun> live = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UserId, RunId> active = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closing;

    public RunSupervisor(AgentRuntime runtime, RunRepository runs, IntentRepository intents, Clock clock,
                         IdGenerator ids, RunSupervisorConfig config) {
        this(runtime, runs, intents, clock, ids, config, AnswerWait.REAL);
    }

    RunSupervisor(AgentRuntime runtime, RunRepository runs, IntentRepository intents, Clock clock, IdGenerator ids,
                  RunSupervisorConfig config, AnswerWait answerWait) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.config = Objects.requireNonNull(config, "config");
        this.answerWait = Objects.requireNonNull(answerWait, "answerWait");
    }

    /** One live run: its phase, its messages, and the conversation the model has seen (never stored, 21.3). */
    private final class LiveRun {
        final RunId id;
        final UserId owner;
        final Instant startedAt;
        final AtomicReference<Phase> phase = new AtomicReference<>(RUNNING);
        final AtomicReference<CompletableFuture<Phase>> settled = new AtomicReference<>(new CompletableFuture<>());
        final List<RunMessage> messages = new CopyOnWriteArrayList<>();
        final AtomicBoolean interrupted = new AtomicBoolean();
        volatile Conversation conversation;
        volatile Instant lastActivity;

        LiveRun(RunId id, UserId owner) {
            this.id = id;
            this.owner = owner;
            this.startedAt = clock.instant();
            this.lastActivity = startedAt;
        }

        void say(String role, String kind, String text) {
            Instant now = clock.instant();
            messages.add(new RunMessage(role, kind, text, now));
            lastActivity = now;
        }

        /** The run thread's end of {@code ask_user} (6.2). */
        UserChannel channel() {
            return (question, timeout) -> ask(question, timeout);
        }

        private Optional<String> ask(String question, Duration timeout) {
            CompletableFuture<String> answer = new CompletableFuture<>();
            Waiting waiting = new Waiting(question, clock.instant(), answer);
            say(RunMessage.AGENT, RunMessage.QUESTION, question);
            CompletableFuture<Phase> old = settled.getAndSet(new CompletableFuture<>());
            phase.set(waiting);
            old.complete(waiting);
            try {
                return Optional.of(answerWait.await(answer, timeout));
            } catch (TimeoutException e) {
                // One side wins the CAS: if the answer's winner got there first, its text is the answer.
                if (phase.compareAndSet(waiting, RUNNING)) {
                    return Optional.empty();
                }
                return Optional.of(answer.join());
            } catch (ExecutionException e) {
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.set(true);
                phase.compareAndSet(waiting, RUNNING);
                return Optional.empty();
            }
        }
    }

    /** Starts a run for the caller and waits for its first settle point (23.1 {@code POST /api/runs}). */
    public RunView start(AuthContext auth, String message) {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(message, "message");
        refuseWhenClosing();
        evict();
        UserId owner = auth.user();
        RunId id = new RunId(ids.next());
        if (active.putIfAbsent(owner, id) != null) {
            throw new RunRefused(RunRefused.Kind.RATE_LIMITED);
        }
        if (overHourlyLimit(owner)) {
            active.remove(owner, id);
            throw new RunRefused(RunRefused.Kind.RATE_LIMITED);
        }
        LiveRun run = new LiveRun(id, owner);
        run.say(RunMessage.USER, RunMessage.MESSAGE, message);
        live.put(id, run);
        CompletableFuture<Phase> first = run.settled.get();
        launch(run, () -> runtime.start(auth, id, message, run.channel(), new RunTrace(id)));
        return await(run, first);
    }

    /**
     * An answer to {@code ask_user}, or a continuation of a settled conversation (23.1 {@code POST .../messages}).
     *
     * @throws RunRefused not the caller's run, nothing waiting for a message, or a limit
     */
    public RunView answer(AuthContext auth, RunId id, String message) {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(message, "message");
        refuseWhenClosing();
        UserId owner = auth.user();
        LiveRun run = live.get(id);
        if (run == null || !run.owner.equals(owner)) {
            if (runs.find(owner, id).isEmpty()) {
                throw new RunRefused(RunRefused.Kind.NOT_FOUND);
            }
            orphanCheck(owner, id);
            throw new RunRefused(RunRefused.Kind.NOT_WAITING);
        }
        Phase now = run.phase.get();
        if (now instanceof Waiting waiting) {
            return answerQuestion(run, waiting, message);
        }
        if (now instanceof Settled settled && settled.resumable() && run.conversation != null) {
            return continueRun(auth, run, settled, message);
        }
        throw new RunRefused(RunRefused.Kind.NOT_WAITING);
    }

    private void refuseWhenClosing() {
        if (closing) {
            throw new RunRefused(RunRefused.Kind.RATE_LIMITED);
        }
    }

    /** Test hook: waits until the run's current settle point, or {@code limit} passes. */
    void awaitSettled(RunId id, Duration limit) throws Exception {
        LiveRun run = live.get(id);
        if (run != null) {
            run.settled.get().get(limit.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private RunView answerQuestion(LiveRun run, Waiting waiting, String message) {
        if (!run.phase.compareAndSet(waiting, RUNNING)) {
            throw new RunRefused(RunRefused.Kind.NOT_WAITING);
        }
        CompletableFuture<Phase> next = new CompletableFuture<>();
        run.settled.set(next);
        run.say(RunMessage.USER, RunMessage.MESSAGE, message);
        waiting.answer().complete(message);
        return await(run, next);
    }

    private RunView continueRun(AuthContext auth, LiveRun run, Settled settled, String message) {
        UserId owner = run.owner;
        if (!run.phase.compareAndSet(settled, RUNNING)) {
            throw new RunRefused(RunRefused.Kind.NOT_WAITING);
        }
        boolean reserved = false;
        try {
            if (runs.turns(owner, run.id) >= config.maxTurns() || overHourlyLimit(owner)
                    || active.putIfAbsent(owner, run.id) != null) {
                throw new RunRefused(RunRefused.Kind.RATE_LIMITED);
            }
            reserved = true;
            runs.addTurn(owner, run.id, clock.instant());
        } catch (RuntimeException e) {
            run.phase.compareAndSet(RUNNING, settled);
            if (reserved) {
                active.remove(owner, run.id);
            }
            throw e;
        }
        CompletableFuture<Phase> next = new CompletableFuture<>();
        run.settled.set(next);
        run.say(RunMessage.USER, RunMessage.MESSAGE, message);
        Conversation history = run.conversation;
        launch(run, () -> runtime.resume(auth, run.id, history, message, run.channel(), new RunTrace(run.id)));
        return await(run, next);
    }

    private boolean overHourlyLimit(UserId owner) {
        return runs.turnsSince(owner, clock.instant().minus(Duration.ofHours(1))) >= config.maxPerHour();
    }

    /** The run on a virtual thread; whatever way it leaves, the run settles and its user is free again. */
    private void launch(LiveRun run, Supplier<RunSession> body) {
        executor.execute(() -> {
            Settled end;
            try {
                RunSession session = body.get();
                if (session.outcome() instanceof RunOutcome.Done done) {
                    run.conversation = session.conversation();
                    if (!done.text().isBlank()) {
                        run.say(RunMessage.AGENT, RunMessage.MESSAGE, done.text());
                    }
                } else {
                    run.conversation = null;
                }
                end = settledFromRecord(run);
            } catch (RuntimeException e) {
                run.conversation = null;
                end = new Settled(RunView.FAILED, Optional.of(INTERNAL_ERROR));
            }
            if (run.interrupted.get()) {
                interrupt(run.owner, run.id);
                run.conversation = null;
                end = new Settled(RunView.FAILED, Optional.of(INTERRUPTED));
            }
            run.lastActivity = clock.instant();
            CompletableFuture<Phase> waiter = run.settled.get();
            run.phase.set(end);
            active.remove(run.owner, run.id);
            waiter.complete(end);
        });
    }

    private Settled settledFromRecord(LiveRun run) {
        return runs.find(run.owner, run.id)
                .flatMap(r -> r.outcome().map(o -> new Settled(o, r.reason())))
                .orElseGet(() -> new Settled(RunView.FAILED, Optional.of(INTERNAL_ERROR)));
    }

    /** Waits for the settle point in {@code future}, up to the response wait; a timeout reads as {@code running}. */
    private RunView await(LiveRun run, CompletableFuture<Phase> future) {
        try {
            future.get(config.runResponseWait().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // Not settled yet: the view says running (or whatever the run is by now).
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return liveView(run);
    }

    /** The run as the caller sees it, or empty when it is not theirs or does not exist (T6). */
    public Optional<RunView> get(AuthContext auth, RunId id) {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(id, "id");
        UserId owner = auth.user();
        LiveRun run = live.get(id);
        if (run != null && run.owner.equals(owner)) {
            return Optional.of(liveView(run));
        }
        orphanCheck(owner, id);
        return runs.find(owner, id).map(r -> {
            Settled s = r.outcome().map(o -> reevaluated(owner, r, new Settled(o, r.reason())))
                    .orElseGet(() -> new Settled(RunView.RUNNING, Optional.empty()));
            return new RunView(id, s.status(), s.reason(), List.of(), false, r.startedAt(), Optional.of(r));
        });
    }

    private RunView liveView(LiveRun run) {
        Phase phase = run.phase.get();
        Optional<RunRecord> record = runs.find(run.owner, run.id);
        String status;
        Optional<String> reason = Optional.empty();
        switch (phase) {
            case Running r -> status = RunView.RUNNING;
            case Waiting w -> status = RunView.WAITING_FOR_USER;
            case Settled s -> {
                Settled seen = record.map(rec -> reevaluated(run.owner, rec, s)).orElse(s);
                status = seen.status();
                reason = seen.reason();
            }
        }
        return new RunView(run.id, status, reason, new ArrayList<>(run.messages), true, run.startedAt, record);
    }

    /** {@code waiting_for_approval} is re-evaluated against the intents' current states on every read (23.1). */
    private Settled reevaluated(UserId owner, RunRecord record, Settled stored) {
        if (!stored.resumable()) {
            return stored;
        }
        for (IntentId intent : record.intentIds()) {
            if (intents.find(owner, intent).map(i -> i.state() == IntentState.AWAITING_APPROVAL).orElse(false)) {
                return new Settled(RunView.WAITING_FOR_APPROVAL, Optional.empty());
            }
        }
        return new Settled(RunView.DONE, Optional.empty());
    }

    /**
     * Ends a stored run that has no outcome, no live run here and no write for {@code orphanAfter}: its instance is
     * gone (a restart or a kill). The owner's own update, so no system read is needed and several instances agree.
     */
    private void orphanCheck(UserId owner, RunId id) {
        LiveRun run = live.get(id);
        if (run != null) {
            return;
        }
        Optional<RunRecord> record = runs.find(owner, id);
        if (record.isEmpty() || record.get().outcome().isPresent()) {
            return;
        }
        Optional<Instant> updated = runs.updatedAt(owner, id);
        if (updated.isPresent() && !updated.get().plus(config.orphanAfter()).isAfter(clock.instant())) {
            endInterrupted(owner, record.get());
        }
    }

    private void interrupt(UserId owner, RunId id) {
        runs.find(owner, id).ifPresent(r -> endInterrupted(owner, r));
    }

    private void endInterrupted(UserId owner, RunRecord r) {
        Instant now = clock.instant();
        Instant ended = r.endedAt().orElse(now.isBefore(r.startedAt()) ? r.startedAt() : now);
        runs.update(owner, new RunRecord(r.id(), r.owner(), r.model(), r.startedAt(), Optional.of(ended),
                Optional.of(RunView.FAILED), Optional.of(INTERRUPTED), r.modelCalls(), r.toolCalls(), r.inputTokens(),
                r.outputTokens(), r.intentIds(), r.toolEvents()));
    }

    /** Drops settled conversations idle for {@code idle}, then the oldest settled ones above {@code maxLive}. */
    private void evict() {
        Instant now = clock.instant();
        live.values().removeIf(r -> r.phase.get() instanceof Settled && !r.lastActivity.plus(config.idle()).isAfter(now));
        if (live.size() >= config.maxLive()) {
            List<LiveRun> settled = new ArrayList<>(live.values().stream().filter(r -> r.phase.get() instanceof Settled)
                    .sorted(Comparator.comparing((LiveRun r) -> r.lastActivity)).toList());
            for (LiveRun r : settled) {
                if (live.size() < config.maxLive()) {
                    break;
                }
                live.remove(r.id, r);
            }
        }
    }

    /** How many runs this instance holds (live or settled but not yet evicted). */
    int held() {
        return live.size();
    }

    /**
     * Shutdown (23.1): every run waiting for the user ends {@code failed}/{@link #INTERRUPTED}, and runs still going are
     * given {@code grace} to end before they are interrupted.
     */
    public void close(Duration grace) {
        closing = true;
        for (LiveRun run : live.values()) {
            if (run.phase.get() instanceof Waiting waiting) {
                run.interrupted.set(true);
                if (run.phase.compareAndSet(waiting, RUNNING)) {
                    waiting.answer().completeExceptionally(new IllegalStateException(INTERRUPTED));
                }
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(grace.toNanos(), TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(30));
    }
}
