package dev.ucplab.runtime;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.agent.RunRepository;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.ToolEvent;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentNotFoundException;
import dev.ucplab.core.intent.IntentState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
 * the run starts and updated when it ends; it holds names, counts and codes, never the prompt, model text or tool
 * output (23.1).
 *
 * <p>A run is single-threaded, on the caller's thread. The runtime holds no lock and no mutable state of its own, so
 * runs can go on side by side, and nothing is held across a model or merchant call (7.8). A
 * {@link ScriptDriftException} from a scripted model ends the record as failed and is thrown on to the harness, which
 * counts the run as aborted.
 */
public final class AgentRuntime {

    private final ModelClient model;
    private final String modelName;
    private final ToolPorts ports;
    private final RunRepository runs;
    private final AgentBudget budget;
    private final Duration askTimeout;
    private final Clock clock;

    /**
     * @param modelName the model id for {@code RunRecord.model}, stored as {@code <modelName>/<prompt version>}
     * @param askTimeout how long {@code ask_user} waits (6.2: 5 minutes by default)
     */
    public AgentRuntime(ModelClient model, String modelName, ToolPorts ports, RunRepository runs, AgentBudget budget,
                        Duration askTimeout, Clock clock) {
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
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(trace, "trace");
        Run run = new Run(auth, id, user, trace);
        runs.insert(auth.user(), RunRecord.started(id, auth.user(), run.modelId, run.start));
        RunOutcome outcome;
        try {
            outcome = run.loop(userMessage);
        } catch (ScriptDriftException e) {
            run.finish(new RunOutcome.Failed("script_drift"));
            throw e;
        }
        run.finish(outcome);
        return outcome;
    }

    /** One run's state, confined to the run's thread. */
    private final class Run {
        private final AuthContext auth;
        private final ToolContext context;
        private final RunTrace trace;
        private final BudgetMeter meter;
        private final ToolRegistry tools;
        private final String modelId;
        private final Instant start;
        private final List<IntentId> intents = new ArrayList<>();
        private final List<ToolEvent> events = new ArrayList<>();

        Run(AuthContext auth, RunId id, UserChannel user, RunTrace trace) {
            this.auth = auth;
            this.context = new ToolContext(auth, id);
            this.trace = trace;
            this.meter = new BudgetMeter(budget, clock);
            this.tools = ToolRegistry.of(ports, (q, t) -> meter.waitingForUser(() -> user.ask(q, t)), askTimeout);
            this.modelId = modelName + "/" + SystemPrompt.VERSION;
            this.start = clock.instant();
        }

        RunOutcome loop(String userMessage) {
            Conversation conversation = new Conversation(SystemPrompt.text(), List.of(new Message.User(userMessage)));
            while (true) {
                Optional<BudgetLimit> refused = meter.beforeModelCall();
                if (refused.isPresent()) {
                    return new RunOutcome.BudgetExceeded(refused.get(), intents);
                }
                Instant before = clock.instant();
                ModelTurn turn;
                try {
                    turn = model.generate(conversation, tools.specs());
                } catch (ScriptDriftException e) {
                    throw e;
                } catch (RuntimeException e) {
                    return new RunOutcome.Failed("model_error");
                }
                meter.afterTurn(turn.usage());
                trace.llmCall(meter.modelCalls(), Duration.between(before, clock.instant()),
                        turn.usage().inputTokens(), turn.usage().outputTokens(), turn.toolCalls().size());
                conversation = conversation.append(new Message.Model(turn.text(), turn.toolCalls()));
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
                        outcome = tool.isPresent() ? tool.get().run(context, call.arguments())
                                : ToolOutcome.error("unknown_tool");
                    } catch (RuntimeException e) {
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
                    if (outcome instanceof ToolOutcome.NoAnswer) {
                        return new RunOutcome.UserTimedOut(
                                call.arguments().get("question") instanceof String q ? q : "");
                    }
                }
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
            Instant end = clock.instant();
            Instant ended = end.isBefore(start) ? start : end;
            runs.update(auth.user(), new RunRecord(context.runId(), auth.user(), modelId, start, Optional.of(ended),
                    Optional.of(status), meter.modelCalls(), meter.toolCalls(), meter.usage().inputTokens(),
                    meter.usage().outputTokens(), intents, events));
            trace.run(status, detail, meter.modelCalls(), meter.toolCalls(), meter.usage().inputTokens(),
                    meter.usage().outputTokens(), Duration.between(start, ended));
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
