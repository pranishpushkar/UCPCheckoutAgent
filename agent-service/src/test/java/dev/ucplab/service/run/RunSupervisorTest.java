package dev.ucplab.service.run;

import static dev.ucplab.service.run.RunFixture.ALICE;
import static dev.ucplab.service.run.RunFixture.BOB;
import static dev.ucplab.service.run.RunFixture.ask;
import static dev.ucplab.service.run.RunFixture.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The run supervisor (23.1, m6-plan section 3.3, C13) over fake models, a fake clock and latches: nothing sleeps, and
 * every wait is on a latch or a future with a generous limit that only a bug reaches.
 */
class RunSupervisorTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final RunSupervisorConfig CONFIG = RunFixture.config(LIMIT);

    private final RunFixture fx = new RunFixture();
    private RunSupervisor supervisor;

    @AfterEach
    void stop() {
        if (supervisor != null) {
            supervisor.close(Duration.ofSeconds(2));
        }
    }

    /** Asks once per run, then says {@code "done"}: stateless, so any number of runs can share it. */
    private static ModelClient askOnce() {
        return (conversation, tools) -> conversation.messages().stream().anyMatch(m -> m instanceof Message.ToolResult)
                ? text("done") : ask("q1", "Which colour?");
    }

    private RunRecord record(RunView view) {
        return fx.runs.find(ALICE.user(), view.id()).orElseThrow();
    }

    // Covers: 23.1, 6.2 (a settle point at ask: the first request returns waiting_for_user with the question, the record
    // is still in progress; the answer then continues the same run to done, and the messages tell the story)
    @Test
    void start_settlesAtAsk_thenTheAnswerFinishesTheRun() throws Exception {
        supervisor = fx.supervisor(askOnce(), CONFIG);

        RunView waiting = supervisor.start(ALICE, "buy a mug");

        assertThat(waiting.status()).isEqualTo("waiting_for_user");
        assertThat(waiting.messages()).extracting(RunMessage::role, RunMessage::kind, RunMessage::text)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("user", "message", "buy a mug"),
                        org.assertj.core.groups.Tuple.tuple("agent", "question", "Which colour?"));
        assertThat(record(waiting).outcome()).as("the record is still in progress").isEmpty();

        RunView done = supervisor.answer(ALICE, waiting.id(), "blue");

        assertThat(done.status()).isEqualTo("done");
        assertThat(done.id()).isEqualTo(waiting.id());
        assertThat(done.messages()).extracting(RunMessage::text)
                .containsExactly("buy a mug", "Which colour?", "blue", "done");
        assertThat(record(done).outcome()).contains("done");
        assertThat(record(done).modelCalls()).isEqualTo(2);
    }

    // Covers: 23.1, 7.8 (50 concurrent answers to one question give exactly one 200; the other 49 are 409
    // run_not_waiting, and the first answer is the one the model sees)
    @Test
    void fiftyConcurrentAnswers_exactlyOneWins() throws Exception {
        CountDownLatch inSecondCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger turn = new AtomicInteger();
        List<Message> seen = new ArrayList<>();
        ModelClient model = (conversation, tools) -> {
            if (turn.incrementAndGet() == 1) {
                return ask("q1", "Which colour?");
            }
            seen.addAll(conversation.messages());
            inSecondCall.countDown();
            await(release);
            return text("done");
        };
        supervisor = fx.supervisor(model, RunFixture.config(Duration.ofMillis(300)));
        RunView waiting = supervisor.start(ALICE, "buy a mug");
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String answer = "answer-" + i;
            results.add(pool.submit(() -> {
                go.await();
                try {
                    return supervisor.answer(ALICE, waiting.id(), answer);
                } catch (RunRefused e) {
                    return e;
                }
            }));
        }
        go.countDown();
        int ok = 0;
        int refused = 0;
        for (Future<Object> f : results) {
            Object r = f.get(LIMIT.toSeconds(), TimeUnit.SECONDS);
            if (r instanceof RunView) {
                ok++;
            } else if (r instanceof RunRefused e && e.kind() == RunRefused.Kind.NOT_WAITING) {
                refused++;
            }
        }
        release.countDown();
        supervisor.awaitSettled(waiting.id(), LIMIT);
        pool.shutdown();

        assertThat(ok).isEqualTo(1);
        assertThat(refused).isEqualTo(49);
        assertThat(inSecondCall.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.stream().filter(m -> m instanceof Message.ToolResult).map(m -> ((Message.ToolResult) m).json())
                .toList()).singleElement().asString().contains("answer-");
        assertThat(record(waiting).modelCalls()).isEqualTo(2);
    }

    /** An answer wait whose timeout the test fires: it holds the run thread until {@code fire} opens. */
    private static final class ControlledWait implements AnswerWait {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch fire = new CountDownLatch(1);
        final AtomicReference<CompletableFuture<String>> answer = new AtomicReference<>();

        @Override
        public String await(CompletableFuture<String> a, Duration timeout) throws TimeoutException,
                InterruptedException {
            answer.set(a);
            entered.countDown();
            fire.await();
            throw new TimeoutException();
        }
    }

    // Covers: 23.1, 6.2 (the timeout and an answer race, the answer first: the timeout fires after the answer's CAS
    // has won, so the run takes the answer and goes on to done; exactly one side wins)
    @Test
    void timeoutRacingAnAnswer_answerFirst_theAnswerWins() throws Exception {
        ControlledWait wait = new ControlledWait();
        List<String> seenAnswers = new ArrayList<>();
        AtomicInteger turn = new AtomicInteger();
        ModelClient model = (conversation, tools) -> {
            if (turn.incrementAndGet() == 1) {
                return ask("q1", "Which colour?");
            }
            conversation.messages().forEach(m -> {
                if (m instanceof Message.ToolResult r) {
                    seenAnswers.add(r.json());
                }
            });
            return text("done");
        };
        supervisor = fx.supervisor(model, CONFIG, wait);
        RunView waiting = supervisor.start(ALICE, "buy a mug");
        assertThat(wait.entered.await(5, TimeUnit.SECONDS)).isTrue();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        Future<RunView> answered = pool.submit(() -> supervisor.answer(ALICE, waiting.id(), "blue"));

        wait.answer.get().get(LIMIT.toSeconds(), TimeUnit.SECONDS);
        wait.fire.countDown();
        RunView view = answered.get(LIMIT.toSeconds(), TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(view.status()).isEqualTo("done");
        assertThat(seenAnswers).singleElement().asString().contains("blue");
        assertThat(record(view).outcome()).contains("done");
    }

    // Covers: 23.1, 6.2 (the same race, the timeout first: the run ends user_timeout, and an answer afterwards is 409
    // run_not_waiting and changes nothing)
    @Test
    void timeoutRacingAnAnswer_timeoutFirst_theTimeoutWins() throws Exception {
        ControlledWait wait = new ControlledWait();
        supervisor = fx.supervisor(askOnce(), CONFIG, wait);
        RunView waiting = supervisor.start(ALICE, "buy a mug");
        assertThat(wait.entered.await(5, TimeUnit.SECONDS)).isTrue();

        wait.fire.countDown();
        supervisor.awaitSettled(waiting.id(), LIMIT);

        assertThatThrownBy(() -> supervisor.answer(ALICE, waiting.id(), "blue")).isInstanceOfSatisfying(
                RunRefused.class, e -> assertThat(e.kind()).isEqualTo(RunRefused.Kind.NOT_WAITING));
        RunView view = supervisor.get(ALICE, waiting.id()).orElseThrow();
        assertThat(view.status()).isEqualTo("user_timeout");
        assertThat(record(view).outcome()).contains("user_timeout");
        assertThat(record(view).modelCalls()).isEqualTo(1);
    }

    // Covers: 23.1, 3.2 (a run that has not settled by runResponseWait is returned as running; the client polls, and the
    // run goes on to done once it is released)
    @Test
    void running_afterTheResponseWait() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ModelClient model = (conversation, tools) -> {
            await(release);
            return text("late");
        };
        supervisor = fx.supervisor(model, RunFixture.config(Duration.ofMillis(100)));

        RunView view = supervisor.start(ALICE, "slow");

        assertThat(view.status()).isEqualTo("running");
        assertThat(supervisor.get(ALICE, view.id()).orElseThrow().status()).isEqualTo("running");
        release.countDown();
        supervisor.awaitSettled(view.id(), LIMIT);
        assertThat(supervisor.get(ALICE, view.id()).orElseThrow().status()).isEqualTo("done");
    }

    // Covers: 23.1, 21.3 (shutdown ends a run waiting for the user as failed with reason interrupted, in the record and in
    // the view)
    @Test
    void shutdown_endsWaitingRuns_failedInterrupted() {
        supervisor = fx.supervisor(askOnce(), CONFIG);
        RunView waiting = supervisor.start(ALICE, "buy a mug");

        supervisor.close(Duration.ofSeconds(5));

        RunRecord record = record(waiting);
        assertThat(record.outcome()).contains("failed");
        assertThat(record.reason()).contains("interrupted");
        RunView view = supervisor.get(ALICE, waiting.id()).orElseThrow();
        assertThat(view.status()).isEqualTo("failed");
        assertThat(view.reason()).contains("interrupted");
        assertThatThrownBy(() -> supervisor.start(ALICE, "again")).isInstanceOf(RunRefused.class);
    }

    // Covers: 23.1, 3.3 (an in-progress record with no live run reads as running until 6 minutes of silence, then ends
    // failed/interrupted: not before, at 359 s; yes at 361 s)
    @Test
    void orphanedRecord_endsInterruptedAfterSixMinutes_notBefore() {
        RunId id = new RunId("orphan-1");
        fx.runs.insert(ALICE.user(), RunRecord.started(id, ALICE.user(), "scripted/v1", RunFixture.T0));
        // The default config: ask timeout 5 min + response wait 60 s = 6 min.
        supervisor = fx.supervisor(askOnce(), RunSupervisorConfig.defaults());

        fx.clock.advance(Duration.ofSeconds(359));
        RunView early = supervisor.get(ALICE, id).orElseThrow();
        assertThat(early.status()).isEqualTo("running");
        assertThat(fx.runs.find(ALICE.user(), id).orElseThrow().outcome()).isEmpty();

        fx.clock.advance(Duration.ofSeconds(2));
        RunView late = supervisor.get(ALICE, id).orElseThrow();
        assertThat(late.status()).isEqualTo("failed");
        assertThat(late.reason()).contains("interrupted");
        assertThat(fx.runs.find(ALICE.user(), id).orElseThrow().outcome()).contains("failed");
        assertThat(late.messagesRetained()).isFalse();
    }

    // Covers: 23.1, 21.3, m6-plan Decisions (an orphaned record does not block its user: the user starts a new run at
    // once, because one active run per user is checked against live runs, not records)
    @Test
    void orphanedRecord_doesNotBlockANewRun() {
        fx.runs.insert(ALICE.user(), RunRecord.started(new RunId("orphan-2"), ALICE.user(), "scripted/v1",
                RunFixture.T0));
        supervisor = fx.supervisor(askOnce(), CONFIG);

        RunView view = supervisor.start(ALICE, "buy a mug");

        assertThat(view.status()).isEqualTo("waiting_for_user");
    }

    // Covers: 23.1, 18.1 (one active run per user: a second start while the first waits is refused as rate_limited, and
    // another user is not affected; once the first ends the user can start again)
    @Test
    void oneActiveRunPerUser() {
        supervisor = fx.supervisor(askOnce(), CONFIG);
        RunView first = supervisor.start(ALICE, "one");

        assertThatThrownBy(() -> supervisor.start(ALICE, "two")).isInstanceOfSatisfying(RunRefused.class,
                e -> assertThat(e.kind()).isEqualTo(RunRefused.Kind.RATE_LIMITED));
        assertThat(supervisor.start(BOB, "bob's").status()).isEqualTo("waiting_for_user");
        supervisor.answer(ALICE, first.id(), "blue");
        assertThat(supervisor.start(ALICE, "three").status()).isNotEmpty();
    }

    // Covers: 23.1, T6 (another user's run is absent for them: get is empty, an answer is not_found)
    @Test
    void anotherUsersRun_isNotFound() {
        supervisor = fx.supervisor(askOnce(), CONFIG);
        RunView alices = supervisor.start(ALICE, "mine");

        assertThat(supervisor.get(BOB, alices.id())).isEmpty();
        assertThatThrownBy(() -> supervisor.answer(BOB, alices.id(), "x")).isInstanceOfSatisfying(RunRefused.class,
                e -> assertThat(e.kind()).isEqualTo(RunRefused.Kind.NOT_FOUND));
        assertThat(supervisor.get(ALICE, alices.id()).orElseThrow().status()).isEqualTo("waiting_for_user");
    }

    // Covers: 7.8 (no lock is held across a model call: while one run's model call is blocked, other requests, including
    // another user's whole run, go through)
    @Test
    void aBlockedModelCall_blocksNobodyElse() throws Exception {
        CountDownLatch inModel = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (conversation, tools) -> {
            if (calls.incrementAndGet() == 1) {
                inModel.countDown();
                await(release);
            }
            return text("hello");
        };
        supervisor = fx.supervisor(model, RunFixture.config(Duration.ofMillis(100)));
        RunView slow = supervisor.start(ALICE, "slow");
        assertThat(inModel.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(supervisor.get(ALICE, slow.id()).orElseThrow().status()).isEqualTo("running");
        assertThat(supervisor.start(BOB, "quick").status()).isEqualTo("done");
        release.countDown();
        supervisor.awaitSettled(slow.id(), LIMIT);
    }

    // Covers: 23.1, P5 (the 11th turn in an hour is refused as rate_limited; an hour later it is allowed)
    @Test
    void eleventhTurnInAnHour_isRefused() {
        supervisor = fx.supervisor((c, t) -> text("ok"), CONFIG);
        for (int i = 0; i < 10; i++) {
            assertThat(supervisor.start(ALICE, "run " + i).status()).isEqualTo("done");
        }

        assertThatThrownBy(() -> supervisor.start(ALICE, "eleventh")).isInstanceOfSatisfying(RunRefused.class,
                e -> assertThat(e.kind()).isEqualTo(RunRefused.Kind.RATE_LIMITED));
        assertThat(supervisor.start(BOB, "bob").status()).isEqualTo("done");
        fx.clock.advance(Duration.ofMinutes(61));
        assertThat(supervisor.start(ALICE, "later").status()).isEqualTo("done");
    }

    // Covers: 23.1, P5, m6-plan Decisions (a continuation after done starts a fresh budget and counts as a turn; the 6th
    // continuation is rate_limited, because a run has at most 6 turns)
    @Test
    void continuations_areCappedAtFive() {
        RunSupervisorConfig roomy = new RunSupervisorConfig(LIMIT, Duration.ofMinutes(5), Duration.ofMinutes(30), 100,
                100, 6);
        supervisor = fx.supervisor((c, t) -> text("ok"), roomy);
        RunView first = supervisor.start(ALICE, "start");

        for (int i = 1; i <= 5; i++) {
            RunView next = supervisor.answer(ALICE, first.id(), "more " + i);
            assertThat(next.status()).as("continuation " + i).isEqualTo("done");
        }

        assertThatThrownBy(() -> supervisor.answer(ALICE, first.id(), "sixth")).isInstanceOfSatisfying(RunRefused.class,
                e -> assertThat(e.kind()).isEqualTo(RunRefused.Kind.RATE_LIMITED));
        assertThat(fx.runs.turns(ALICE.user(), first.id())).isEqualTo(6);
        assertThat(supervisor.get(ALICE, first.id()).orElseThrow().status()).as("still resumable state").isEqualTo("done");
    }

    // Covers: 23.1, P5 (continuations count toward the hourly limit with starts)
    @Test
    void continuations_countTowardTheHourlyLimit() {
        supervisor = fx.supervisor((c, t) -> text("ok"), CONFIG);
        RunView first = supervisor.start(ALICE, "start");
        for (int i = 1; i <= 5; i++) {
            supervisor.answer(ALICE, first.id(), "more " + i);
        }
        for (int i = 0; i < 4; i++) {
            supervisor.start(ALICE, "other " + i);
        }

        assertThat(fx.runs.turnsSince(ALICE.user(), RunFixture.T0.minusSeconds(1))).isEqualTo(10);
        assertThatThrownBy(() -> supervisor.start(ALICE, "eleventh")).isInstanceOfSatisfying(RunRefused.class,
                e -> assertThat(e.kind()).isEqualTo(RunRefused.Kind.RATE_LIMITED));
    }

    // Covers: 23.1 (a message to a run that ended budget_exceeded, failed or user_timeout is 409 run_not_waiting)
    @Test
    void messageAfterAnUnresumableEnd_isNotWaiting() throws Exception {
        AtomicInteger n = new AtomicInteger();
        supervisor = fx.supervisor((c, t) -> {
            int call = n.incrementAndGet();
            if (call <= 6) {
                return ask("a" + call, "again?");
            }
            return text("never");
        }, CONFIG);
        RunView view = supervisor.start(ALICE, "ask forever");
        for (int i = 0; i < 5; i++) {
            view = supervisor.answer(ALICE, view.id(), "yes");
        }
        view = supervisor.answer(ALICE, view.id(), "yes");
        RunId id = view.id();

        assertThat(view.status()).isEqualTo("budget_exceeded");
        assertThat(view.reason()).contains("MODEL_CALLS");
        assertThatThrownBy(() -> supervisor.answer(ALICE, id, "more")).isInstanceOfSatisfying(RunRefused.class,
                e -> assertThat(e.kind()).isEqualTo(RunRefused.Kind.NOT_WAITING));

        RunSupervisor failing = fx.supervisor((c, t) -> {
            throw new IllegalStateException("model down");
        }, CONFIG);
        try {
            RunView failed = failing.start(BOB, "x");
            assertThat(failed.status()).isEqualTo("failed");
            assertThatThrownBy(() -> failing.answer(BOB, failed.id(), "more")).isInstanceOfSatisfying(RunRefused.class,
                    e -> assertThat(e.kind()).isEqualTo(RunRefused.Kind.NOT_WAITING));
        } finally {
            failing.close(Duration.ofSeconds(2));
        }
    }

    // Covers: 21.3, 23.1 (after a restart the run is on record but its conversation is gone: get shows no messages and
    // messages_retained false, and a message is 409 run_not_waiting)
    @Test
    void afterARestart_theConversationIsGone() {
        supervisor = fx.supervisor((c, t) -> text("ok"), CONFIG);
        RunView done = supervisor.start(ALICE, "hello");
        RunSupervisor after = fx.restarted((c, t) -> text("ok"), CONFIG);
        try {
            RunView view = after.get(ALICE, done.id()).orElseThrow();

            assertThat(view.status()).isEqualTo("done");
            assertThat(view.messages()).isEmpty();
            assertThat(view.messagesRetained()).isFalse();
            assertThatThrownBy(() -> after.answer(ALICE, done.id(), "more")).isInstanceOfSatisfying(RunRefused.class,
                    e -> assertThat(e.kind()).isEqualTo(RunRefused.Kind.NOT_WAITING));
        } finally {
            after.close(Duration.ofSeconds(2));
        }
    }

    // Covers: 21.3, 23.1 (a settled conversation idle for 30 minutes is dropped: the next message is 409, and the
    // view has no messages)
    @Test
    void idleConversation_isDroppedAfterThirtyMinutes() {
        supervisor = fx.supervisor((c, t) -> text("ok"), CONFIG);
        RunView done = supervisor.start(ALICE, "hello");

        fx.clock.advance(Duration.ofMinutes(31));
        supervisor.start(BOB, "someone else triggers the sweep");

        assertThatThrownBy(() -> supervisor.answer(ALICE, done.id(), "more")).isInstanceOfSatisfying(RunRefused.class,
                e -> assertThat(e.kind()).isEqualTo(RunRefused.Kind.NOT_WAITING));
        assertThat(supervisor.get(ALICE, done.id()).orElseThrow().messagesRetained()).isFalse();
    }

    // Covers: 21.3 (at most maxLive conversations are held; the oldest settled one goes first)
    @Test
    void liveRuns_areCapped_oldestSettledFirst() {
        RunSupervisorConfig small = new RunSupervisorConfig(LIMIT, Duration.ofMinutes(5), Duration.ofMinutes(30), 3, 100,
                6);
        supervisor = fx.supervisor((c, t) -> text("ok"), small);
        List<RunView> views = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fx.clock.advance(Duration.ofSeconds(1));
            views.add(supervisor.start(RunFixture.auth("user" + i), "hi"));
        }

        assertThat(supervisor.held()).isLessThanOrEqualTo(3);
        assertThat(supervisor.get(RunFixture.auth("user4"), views.get(4).id()).orElseThrow().messagesRetained()).isTrue();
        assertThat(supervisor.get(RunFixture.auth("user0"), views.get(0).id()).orElseThrow().messagesRetained())
                .isFalse();
    }

    // Covers: 23.1 (a continuation resumes with the earlier messages and the new one last)
    @Test
    void continuation_resumesTheConversation() {
        List<String> lastUser = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        supervisor = fx.supervisor((c, t) -> {
            lastUser.add(RunFixture.lastUserText(c));
            sizes.add(c.messages().size());
            return text("reply " + sizes.size());
        }, CONFIG);
        RunView first = supervisor.start(ALICE, "one");

        RunView second = supervisor.answer(ALICE, first.id(), "two");

        assertThat(lastUser).containsExactly("one", "two");
        assertThat(sizes).containsExactly(1, 3);
        assertThat(second.messages()).extracting(RunMessage::text).containsExactly("one", "reply 1", "two", "reply 2");
        assertThat(second.record().orElseThrow().modelCalls()).isEqualTo(2);
        assertThat(Optional.of(second.status())).contains("done");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
