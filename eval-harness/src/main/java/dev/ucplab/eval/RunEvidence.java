package dev.ucplab.eval;

import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.faultproxy.LogEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything one run leaves behind (m4-plan section 5), collected after the intent is terminal and the proxy is quiet.
 * O ({@link #orders}) and G ({@link #sessions}) are the merchant's ground truth; L ({@link #proxyLog}) is the wire in
 * front of it; A ({@link #approvals}) is the approver's own log; D ({@link #intents}) is what the agent persisted, used
 * only for the expected state, attribution and I4.
 *
 * @param owner the user the harness signed in as for this run: the only user who may decide its approvals (I3, I10)
 * @param proposed the intent id each {@code propose} call returned, in call order
 * @param aborted why the run was aborted, if it was; an aborted run fails every check
 * @param crashes how many times the agent process was killed during the run (S9, S28): each extends a 7.6 bound
 * @param children every agent child process of the run, in start order, with its log
 * @param agentRun what the agent runtime did, for an agent row (M5): its run record and trace (m5-plan section 9)
 */
public record RunEvidence(
        String scenarioId,
        int run,
        long seed,
        int lane,
        UserId owner,
        Optional<String> aborted,
        List<IntentId> proposed,
        List<Persisted> intents,
        List<GroundTruth.Order> orders,
        Map<String, GroundTruth.Session> sessions,
        List<LogEntry> proxyLog,
        List<AutoApprover.Decision> approvals,
        Instant startedAt,
        Optional<Instant> terminalSeenAt,
        int crashes,
        List<Child> children,
        Optional<AgentRun> agentRun) {

    /**
     * One agent run as evidence: the stored {@link RunRecord}, the run's {@code trace.jsonl}, and how it ended as
     * types and codes only. Never the model's text, the question the agent asked, the user's message or answers, tool
     * arguments or tool results (I8, 23.1).
     *
     * @param outcomeType the {@code RunOutcome} type ({@code Done}, {@code BudgetExceeded}, {@code UserTimedOut},
     *     {@code Failed}), or {@code aborted} when the run threw
     * @param breach the limit a {@code BudgetExceeded} run breached
     * @param asks how many times the agent called {@code ask_user}
     * @param intentsAtFirstAsk how many intents the user had when the first {@code ask_user} arrived (S12)
     */
    public record AgentRun(RunRecord record, String traceJsonl, String outcomeType, Optional<String> breach, int asks,
                           Optional<Integer> intentsAtFirstAsk) {
        public AgentRun {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(traceJsonl, "traceJsonl");
            Objects.requireNonNull(outcomeType, "outcomeType");
            Objects.requireNonNull(breach, "breach");
            Objects.requireNonNull(intentsAtFirstAsk, "intentsAtFirstAsk");
        }
    }

    /**
     * One agent child process (m4-plan section 8): the worker id it printed, the database file it ran on, how it
     * ended (killed with kill -9, or stopped at the end of the run) and its exit value, and its stdout and stderr.
     */
    public record Child(String workerId, String dbFile, boolean killed, int exitValue, String log) {
        public Child {
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(dbFile, "dbFile");
            Objects.requireNonNull(log, "log");
        }
    }

    /** D for one intent: its final row, its attempts, its events and its approval rows. */
    public record Persisted(PurchaseIntent intent, List<MerchantAttempt> attempts, List<IntentEvent> events,
                            List<ApprovalRecord> approvalRows) {
        public Persisted {
            Objects.requireNonNull(intent, "intent");
            attempts = List.copyOf(attempts);
            events = List.copyOf(events);
            approvalRows = List.copyOf(approvalRows);
        }
    }

    public RunEvidence {
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(aborted, "aborted");
        proposed = List.copyOf(proposed);
        intents = List.copyOf(intents);
        orders = List.copyOf(orders);
        sessions = Map.copyOf(sessions);
        proxyLog = List.copyOf(proxyLog);
        approvals = List.copyOf(approvals);
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(terminalSeenAt, "terminalSeenAt");
        if (crashes < 0) {
            throw new IllegalArgumentException("crashes must not be negative: " + crashes);
        }
        children = List.copyOf(children);
        Objects.requireNonNull(agentRun, "agentRun");
    }

    /** A run with no agent runtime: an orchestrator-direct row. */
    public RunEvidence(String scenarioId, int run, long seed, int lane, UserId owner, Optional<String> aborted,
                       List<IntentId> proposed, List<Persisted> intents, List<GroundTruth.Order> orders,
                       Map<String, GroundTruth.Session> sessions, List<LogEntry> proxyLog,
                       List<AutoApprover.Decision> approvals, Instant startedAt, Optional<Instant> terminalSeenAt,
                       int crashes, List<Child> children) {
        this(scenarioId, run, seed, lane, owner, aborted, proposed, intents, orders, sessions, proxyLog, approvals,
                startedAt, terminalSeenAt, crashes, children, Optional.empty());
    }

    /** A run of the agent in this process: no crash, no child. */
    public RunEvidence(String scenarioId, int run, long seed, int lane, UserId owner, Optional<String> aborted,
                       List<IntentId> proposed, List<Persisted> intents, List<GroundTruth.Order> orders,
                       Map<String, GroundTruth.Session> sessions, List<LogEntry> proxyLog,
                       List<AutoApprover.Decision> approvals, Instant startedAt, Optional<Instant> terminalSeenAt) {
        this(scenarioId, run, seed, lane, owner, aborted, proposed, intents, orders, sessions, proxyLog, approvals,
                startedAt, terminalSeenAt, 0, List.of());
    }

    /** From the start of the run to the first time the harness saw every intent terminal. */
    public Optional<Duration> timeToTerminal() {
        return terminalSeenAt.map(t -> Duration.between(startedAt, t));
    }
}
