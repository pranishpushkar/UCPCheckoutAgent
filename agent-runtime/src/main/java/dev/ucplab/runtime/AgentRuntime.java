package dev.ucplab.runtime;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelFailure;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.agent.RunRepository;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.ToolEvent;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.BuyerCountry;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentNotFoundException;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.recovery.Sleeper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The agent loop (m5-plan section 4, 6.3, 6.5). For one run: the system prompt and the user's message, then model turns;
 * each tool call a turn asks for runs in order, and its result goes back to the model. The run ends when a turn has no
 * tool call ({@link RunOutcome.Done}), when a limit refuses the next model or tool call
 * ({@link RunOutcome.BudgetExceeded}; the rest of that turn's calls do not run), when {@code ask_user} has no answer
 * ({@link RunOutcome.UserTimedOut}), or when the model call or a tool fails ({@link RunOutcome.Failed}, not retried).
 *
 * <p>Every model and tool call is checked against the run's {@link BudgetMeter} first, and time waiting for the user is
 * left out of the wall clock. The user is the run's {@link AuthContext}, passed to each tool in its
 * {@link ToolContext}, never read from the model (T1). The run's {@link RunRecord} is stored under that user (T2) when
 * the run starts, updated after each tool call (so a run waiting in {@code ask_user} already shows its tool events and
 * intents) and when it ends; it holds names, counts and codes, never the prompt, model text or tool
 * output (23.1).
 *
 * <p>{@link #resume} continues a settled conversation under a fresh budget; {@link #start} and {@code resume} return
 * the final {@link Conversation} in a {@link RunSession}. A run is single-threaded, on the caller's thread. The runtime holds no lock and no mutable state of its own, so
 * runs can go on side by side, and nothing is held across a model or merchant call (7.8). A
 * {@link ScriptDriftException} from a scripted model ends the record as failed and is thrown on to the harness, which
 * counts the run as aborted.
 *
 * <p>The record always ends, whatever way {@code run} leaves. Any other {@link RuntimeException} that escapes the loop,
 * or that the {@code waiting_for_approval} check throws while the run is finishing, ends the record as failed with
 * reason {@code internal_error}, and is then thrown on. Its message goes into neither the record nor the trace.
 */
public final class AgentRuntime {

    private static final String INTERNAL_ERROR = "internal_error";
    /** 6.3: the wait before trying a failed model call again when the API named none. */
    static final Duration RETRY_WAIT = Duration.ofSeconds(2);
    /** 6.3: the time kept for the retried call itself; a wait that leaves less is not made. */
    static final Duration RETRY_CALL = Duration.ofSeconds(5);

    private final ModelClient model;
    private final String modelName;
    private final ToolPorts ports;
    private final RunRepository runs;
    private final AgentBudget budget;
    private final Duration askTimeout;
    private final Clock clock;
    private final Sleeper sleeper;

    /**
     * @param modelName the model id for {@code RunRecord.model}, stored as {@code <modelName>/<prompt version>}
     * @param askTimeout how long {@code ask_user} waits (6.2: 5 minutes by default)
     */
    public AgentRuntime(ModelClient model, String modelName, ToolPorts ports, RunRepository runs, AgentBudget budget,
                        Duration askTimeout, Clock clock) {
        this(model, modelName, ports, runs, budget, askTimeout, clock, d -> Thread.sleep(d));
    }

    /** @param sleeper where the run waits before it tries a failed model call again (6.3); tests move a clock */
    public AgentRuntime(ModelClient model, String modelName, ToolPorts ports, RunRepository runs, AgentBudget budget,
                        Duration askTimeout, Clock clock, Sleeper sleeper) {
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.model = Objects.requireNonNull(model, "model");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.askTimeout = Objects.requireNonNull(askTimeout, "askTimeout");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RunOutcome run(AuthContext auth, RunId id, String userMessage, UserChannel user) {
        return run(auth, id, userMessage, user, new RunTrace(id));
    }

    /** As {@link #run(AuthContext, RunId, String, UserChannel)}, writing the run's spans to {@code trace}. */
    public RunOutcome run(AuthContext auth, RunId id, String userMessage, UserChannel user, RunTrace trace) {
        return start(auth, id, userMessage, user, trace).outcome();
    }

    /** Starts a run with no budget. */
    public RunSession start(AuthContext auth, RunId id, String userMessage, UserChannel user, RunTrace trace) {
        return start(auth, id, userMessage, user, trace, Optional.empty());
    }

    /** Starts a run with the user's budget, for US. */
    public RunSession start(AuthContext auth, RunId id, String userMessage, UserChannel user, RunTrace trace,
                            Optional<Budget> userBudget) {
        return start(auth, id, userMessage, user, trace, userBudget, BuyerCountry.US);
    }

    /**
     * Starts a run, as {@link #run(AuthContext, RunId, String, UserChannel, RunTrace)}, and returns its conversation too.
     * {@code userBudget} is the user's own budget for the run (23.1, P9) and {@code shipTo} the run's buyer country
     * (23.1, 26.7), the user's own choice or the operator's default; never anything the model said.
     */
    public RunSession start(AuthContext auth, RunId id, String userMessage, UserChannel user, RunTrace trace,
                            Optional<Budget> userBudget, BuyerCountry shipTo) {
        check(auth, id, userMessage, user, trace);
        Objects.requireNonNull(userBudget, "userBudget");
        Objects.requireNonNull(shipTo, "shipTo");
        Run run = new Run(auth, id, user, trace, Optional.empty(), userBudget, shipTo);
        runs.insert(auth.user(), RunRecord.started(id, auth.user(), run.modelId, run.start, userBudget, shipTo));
        return run.execute(new Conversation(SystemPrompt.text(userBudget), List.of()), userMessage);
    }

    /**
     * Continues a settled run's conversation with the user's next message, under a fresh {@link BudgetMeter} (6.3, m6-plan
     * Decision 3). The stored record is the run's own: its counts, intents and tool events carry on, and it goes back to
     * "in progress" until this call ends it. An answer to {@code ask_user} is not a resume: that wait happens inside the
     * run, under the same meter.
     *
     * @throws IllegalArgumentException if the caller has no run with this id
     */
    public RunSession resume(AuthContext auth, RunId id, Conversation history, String userMessage, UserChannel user,
                             RunTrace trace) {
        check(auth, id, userMessage, user, trace);
        Objects.requireNonNull(history, "history");
        RunRecord stored = runs.find(auth.user(), id)
                .orElseThrow(() -> new IllegalArgumentException("no such run for this user"));
        Run run = new Run(auth, id, user, trace, Optional.of(stored), stored.budget(), stored.shipTo());
        run.snapshot();
        // The hint is rebuilt from the stored budget, never carried over from the history (6.4); the earlier turns'
        // tool results reach the model as one-line summaries (6.3 Earlier turns in the context).
        return run.execute(new Conversation(SystemPrompt.text(stored.budget()), EarlierTurns.compact(history.messages())),
                userMessage);
    }

    private static void check(AuthContext auth, RunId id, String userMessage, UserChannel user, RunTrace trace) {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(trace, "trace");
    }

    /** The refusals after which the rest of a turn's tool calls are stopped (6.2): P2, P3 and P9. */
    private static final Set<String> STOPPING_REFUSALS = Set.of("policy_P2", "policy_P3", "policy_P9");

    /** One turn of a run, confined to the run's thread: a start, or one continuation (6.2 "Stopped for the turn"). */
    private final class Run {
        private final AuthContext auth;
        /** Its budget is the run's stored one, read again before every model call (23.1 PATCH budget). */
        private ToolContext context;
        /** True after a P2, P3 or P9 refusal: the turn's later tool calls are answered "stopped" without running. */
        private boolean stopped;
        private final RunTrace trace;
        private final BudgetMeter meter;
        private final ToolRegistry tools;
        private final String modelId;
        /** When the run's record began; a resumed run keeps the original. */
        private final Instant start;
        /** When this call began, for the trace's duration and the fresh meter. */
        private final Instant begun;
        private final List<IntentId> intents = new ArrayList<>();
        private final List<ToolEvent> events = new ArrayList<>();
        private final int baseModelCalls;
        private final int baseToolCalls;
        private final long baseInputTokens;
        private final long baseOutputTokens;
        private Conversation conversation;

        Run(AuthContext auth, RunId id, UserChannel user, RunTrace trace, Optional<RunRecord> earlier,
            Optional<Budget> userBudget, BuyerCountry shipTo) {
            this.auth = auth;
            this.context = new ToolContext(auth, id, userBudget, shipTo);
            this.trace = trace;
            this.meter = new BudgetMeter(budget, clock);
            this.tools = ToolRegistry.of(ports, (q, t) -> meter.waitingForUser(() -> user.ask(q, t)), askTimeout);
            this.modelId = modelName + "/" + SystemPrompt.VERSION;
            this.begun = clock.instant();
            this.start = earlier.map(RunRecord::startedAt).orElse(begun);
            earlier.ifPresent(r -> {
                intents.addAll(r.intentIds());
                events.addAll(r.toolEvents());
            });
            this.baseModelCalls = earlier.map(RunRecord::modelCalls).orElse(0);
            this.baseToolCalls = earlier.map(RunRecord::toolCalls).orElse(0);
            this.baseInputTokens = earlier.map(RunRecord::inputTokens).orElse(0L);
            this.baseOutputTokens = earlier.map(RunRecord::outputTokens).orElse(0L);
        }

        /** Runs the loop from {@code history} plus the user's message, and ends the record whichever way it leaves. */
        RunSession execute(Conversation history, String userMessage) {
            RunOutcome outcome;
            try {
                outcome = loop(history.append(new Message.User(userMessage)));
            } catch (ScriptDriftException e) {
                finishFailed("script_drift", e);
                throw e;
            } catch (RuntimeException e) {
                finishFailed(INTERNAL_ERROR, e);
                throw e;
            }
            try {
                finish(outcome);
            } catch (RuntimeException e) {
                finishFailed(INTERNAL_ERROR, e);
                throw e;
            }
            return new RunSession(outcome, conversation);
        }

        /** The record as it stands, still in progress: tool events and intents show while the run waits (23.1). */
        void snapshot() {
            runs.update(auth.user(), new RunRecord(context.runId(), auth.user(), modelId, start, Optional.empty(),
                    Optional.empty(), Optional.empty(), baseModelCalls + meter.modelCalls(),
                    baseToolCalls + meter.toolCalls(), baseInputTokens + meter.usage().inputTokens(),
                    baseOutputTokens + meter.usage().outputTokens(), intents, events).withShipTo(context.shipTo()));
        }

        RunOutcome loop(Conversation initial) {
            conversation = initial;
            boolean retried = false;
            while (true) {
                Optional<BudgetLimit> refused = meter.beforeModelCall();
                if (refused.isPresent()) {
                    return new RunOutcome.BudgetExceeded(refused.get(), intents);
                }
                refreshBudget();
                Instant before = clock.instant();
                ModelTurn turn;
                try {
                    // 6.3: a search the run has moved past is sent as its summary and ids; the record keeps it whole.
                    turn = model.generate(new Conversation(conversation.systemPrompt(),
                            EarlierTurns.superseded(conversation.messages())), tools.specs());
                } catch (ScriptDriftException e) {
                    throw e;
                } catch (RuntimeException e) {
                    // The trace keeps the failure's fixed code and cause, so a 503, a timeout and a blocked answer
                    // can be told apart; the record's reason stays model_error, with the cause beside it (6.5).
                    trace.llmCallFailed(meter.modelCalls(), Duration.between(before, clock.instant()), e);
                    Optional<Duration> wait = retried ? Optional.empty() : retryWait(e);
                    if (wait.isPresent() && waited(wait.get())) {
                        retried = true;
                        continue;
                    }
                    return new RunOutcome.Failed("model_error", Optional.of(RunTrace.causeOf(e)));
                }
                retried = false;
                meter.afterTurn(turn.usage());
                trace.llmCall(meter.modelCalls(), Duration.between(before, clock.instant()),
                        turn.usage().inputTokens(), turn.usage().outputTokens(), turn.toolCalls().size());
                conversation = conversation.append(new Message.Model(turn.text(), turn.toolCalls()));
                snapshot();
                if (turn.toolCalls().isEmpty()) {
                    return new RunOutcome.Done(turn.text(), intents);
                }
                for (ToolCall call : turn.toolCalls()) {
                    refused = meter.beforeToolCall();
                    if (refused.isPresent()) {
                        return new RunOutcome.BudgetExceeded(refused.get(), intents);
                    }
                    Optional<Tool> tool = tools.find(call.name());
                    String name = tool.isPresent() ? call.name() : "unknown";
                    Instant called = clock.instant();
                    ToolOutcome outcome;
                    try {
                        outcome = stopped ? ToolOutcome.stopped()
                                : tool.isPresent() ? tool.get().run(context, call.arguments())
                                : ToolOutcome.error("unknown_tool");
                    } catch (RuntimeException e) {
                        // 11: the line names the tool, the exception's class and where it was thrown; never its message.
                        trace.toolCallFailed(meter.toolCalls(), name, Duration.between(called, clock.instant()), e);
                        return new RunOutcome.Failed("tool_error");
                    }
                    Duration took = Duration.between(called, clock.instant());
                    events.add(new ToolEvent(name, outcome.code(), took.isNegative() ? Duration.ZERO : took));
                    trace.toolCall(meter.toolCalls(), name, took, outcome.code());
                    if (outcome instanceof ToolOutcome.Result r && r.intent().isPresent()
                            && !intents.contains(r.intent().get())) {
                        intents.add(r.intent().get());
                    }
                    conversation = conversation.append(new Message.ToolResult(call.id(), call.name(), outcome.json()));
                    stopped = stopped || refusedByPolicy(call.name(), outcome);
                    snapshot();
                    if (outcome instanceof ToolOutcome.NoAnswer) {
                        return new RunOutcome.UserTimedOut(
                                call.arguments().get("question") instanceof String q ? q : "");
                    }
                }
            }
        }

        /**
         * 23.1, 6.4: the run's stored budget, which the owner may have changed since the last model call, for the tools
         * and the hint; read fresh every time (20 caches nothing). A run the store no longer returns keeps its budget.
         */
        private void refreshBudget() {
            Optional<Budget> stored = runs.find(auth.user(), context.runId()).map(RunRecord::budget)
                    .orElse(context.budget());
            if (!stored.equals(context.budget())) {
                context = new ToolContext(auth, context.runId(), stored, context.shipTo());
                conversation = new Conversation(SystemPrompt.text(stored), conversation.messages());
            }
        }

        /** 6.2: a propose_purchase answer whose intent a P2, P3 or P9 refusal ended. */
        private static boolean refusedByPolicy(String tool, ToolOutcome outcome) {
            if (!tool.equals("propose_purchase") || !(outcome instanceof ToolOutcome.Result r)) {
                return false;
            }
            return CanonicalJson.readObject(r.json()).get("failure_reason") instanceof String reason
                    && STOPPING_REFUSALS.contains(reason);
        }

        /**
         * 6.3: how long to wait before trying a failed model call once more, or empty when it is not tried again: only
         * a retryable failure (HTTP 429, 503 or 504, or the client's own timeout), only with a model call left, and only
         * when the wait, the API's {@code retryDelay} or {@link AgentRuntime#RETRY_WAIT}, plus
         * {@link AgentRuntime#RETRY_CALL} for the call, still fits in the wall clock left.
         */
        private Optional<Duration> retryWait(RuntimeException failure) {
            if (!(failure instanceof ModelFailure f) || !f.retryable() || !meter.modelCallLeft()) {
                return Optional.empty();
            }
            Duration wait = f.retryAfter().filter(d -> !d.isNegative()).orElse(RETRY_WAIT);
            return wait.plus(RETRY_CALL).compareTo(meter.remaining()) <= 0 ? Optional.of(wait) : Optional.empty();
        }

        /** Waits before the retry; an interrupted wait is no retry, and the interrupt is kept. */
        private boolean waited(Duration wait) {
            try {
                sleeper.sleep(wait);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void finish(RunOutcome outcome) {
            String status = switch (outcome) {
                case RunOutcome.Done d -> awaitingApproval() ? "waiting_for_approval" : "done";
                case RunOutcome.BudgetExceeded b -> "budget_exceeded";
                case RunOutcome.UserTimedOut u -> "user_timeout";
                case RunOutcome.Failed f -> "failed";
            };
            String detail = switch (outcome) {
                case RunOutcome.BudgetExceeded b -> b.which().name();
                case RunOutcome.Failed f -> f.reason();
                default -> "";
            };
            end(status, detail, outcome instanceof RunOutcome.Failed f ? f.cause() : Optional.empty());
        }

        /**
         * Ends the record as failed with {@code reason}, without the {@code waiting_for_approval} check. If writing the
         * record or the trace throws too, that is added to {@code cause} as suppressed, so the caller rethrows
         * {@code cause}.
         */
        void finishFailed(String reason, RuntimeException cause) {
            try {
                end("failed", reason, Optional.empty());
            } catch (RuntimeException e) {
                cause.addSuppressed(e);
            }
        }

        private void end(String status, String detail, Optional<String> cause) {
            Instant end = clock.instant();
            Instant ended = end.isBefore(start) ? start : end;
            Optional<String> reason = detail.isEmpty() ? Optional.empty() : Optional.of(detail);
            runs.update(auth.user(), new RunRecord(context.runId(), auth.user(), modelId, start, Optional.of(ended),
                    Optional.of(status), reason, baseModelCalls + meter.modelCalls(), baseToolCalls + meter.toolCalls(),
                    baseInputTokens + meter.usage().inputTokens(), baseOutputTokens + meter.usage().outputTokens(),
                    intents, events).withCause(cause).withShipTo(context.shipTo()));
            trace.run(status, detail, cause, baseModelCalls + meter.modelCalls(), baseToolCalls + meter.toolCalls(),
                    baseInputTokens + meter.usage().inputTokens(), baseOutputTokens + meter.usage().outputTokens(),
                    Duration.between(begun, ended));
        }

        /** True when an intent of this run waits for the user's approval (23.1 {@code waiting_for_approval}). */
        private boolean awaitingApproval() {
            for (IntentId intent : intents) {
                try {
                    if (ports.orchestrator().status(auth, intent).state() == IntentState.AWAITING_APPROVAL) {
                        return true;
                    }
                } catch (IntentNotFoundException e) {
                    // Not this user's; cannot happen for an intent the run proposed.
                }
            }
            return false;
        }
    }
}
