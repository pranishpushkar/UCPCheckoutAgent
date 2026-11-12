package dev.ucplab.eval;

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
        Optional<Instant> terminalSeenAt) {

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
    }

    /** From the start of the run to the first time the harness saw every intent terminal. */
    public Optional<Duration> timeToTerminal() {
        return terminalSeenAt.map(t -> Duration.between(startedAt, t));
    }
}
