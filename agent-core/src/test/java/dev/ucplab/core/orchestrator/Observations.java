package dev.ucplab.core.orchestrator;

import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.fake.CallRecord;
import dev.ucplab.core.merchant.fake.MerchantCall;
import dev.ucplab.core.merchant.fake.ModelOrder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything one executed schedule left behind, for {@link Invariants}. The merchant side ({@code orders},
 * {@code merchantLog}, and {@code calls} as seen on the wire in front of FaultyMerchantModel) and the user's own
 * decisions are the evidence; the orchestrator's repositories are only sampled for intent states (I5) and the stored
 * session id (I4). {@code seq} numbers order calls, decisions, crashes and samples on one timeline.
 *
 * @param intentsBySlot the intent created for each slot's basket
 * @param aborts reasons the run could not finish (the orchestrator threw, or the run did not settle)
 * @param config the orchestrator settings the run used (deadlines, lease, timeouts for I5)
 */
record Observations(
        Map<Integer, IntentId> intentsBySlot,
        List<ClientCall> calls,
        List<CallRecord> merchantLog,
        List<ModelOrder> orders,
        List<Decision> decisions,
        List<CrashEvent> crashes,
        List<Sample> samples,
        List<String> aborts,
        OrchestratorConfig config,
        Map<Integer, List<dev.ucplab.core.intent.ProposedLine>> baskets) {

    /** The property runs' form: each slot's basket is {@link ScheduleExecutor#SLOT_LINES}. */
    Observations(
            Map<Integer, IntentId> intentsBySlot, List<ClientCall> calls, List<CallRecord> merchantLog,
            List<ModelOrder> orders, List<Decision> decisions, List<CrashEvent> crashes, List<Sample> samples,
            List<String> aborts, OrchestratorConfig config) {
        this(intentsBySlot, calls, merchantLog, orders, decisions, crashes, samples, aborts, config,
                slotBaskets(intentsBySlot));
    }

    private static Map<Integer, List<dev.ucplab.core.intent.ProposedLine>> slotBaskets(Map<Integer, IntentId> slots) {
        Map<Integer, List<dev.ucplab.core.intent.ProposedLine>> out = new java.util.HashMap<>();
        slots.keySet().forEach(slot -> out.put(slot, ScheduleExecutor.SLOT_LINES.get(slot)));
        return out;
    }

    Observations {
        intentsBySlot = Map.copyOf(intentsBySlot);
        calls = List.copyOf(calls);
        merchantLog = List.copyOf(merchantLog);
        orders = List.copyOf(orders);
        decisions = List.copyOf(decisions);
        crashes = List.copyOf(crashes);
        samples = List.copyOf(samples);
        aborts = List.copyOf(aborts);
        baskets = Map.copyOf(baskets);
    }

    /**
     * One request as it reached the front of the merchant (after the worker, before fault injection), with what the
     * client got back. For {@code complete}, {@code sessionBefore} is the merchant's own session just before it.
     */
    record ClientCall(
            int seq, Instant at, MerchantCall call, Optional<SessionId> session, Optional<IdempotencyKey> key,
            String body, Optional<CheckoutView> sessionBefore, CallOutcome<CheckoutView> outcome) {}

    /** A decision the user made that the approval service accepted; {@code staleToken} marks a replayed token. */
    record Decision(
            int seq, Instant at, IntentId intent, boolean approve, ApprovalSnapshot snapshot, String snapshotHash,
            boolean staleToken) {}

    record CrashEvent(int seq, Instant at, IntentId intent, String where) {}

    enum Phase { BEFORE_STEP, AFTER_STEP, END }

    /**
     * The intent as stored, before or after one step, or once the run has drained. {@code approvalExpiresAt} is the
     * expiry of the approval the user's channel shows as pending, if any.
     */
    record Sample(
            int seq, Instant at, IntentId intent, Phase phase, IntentState state, Optional<SessionId> session,
            Optional<Instant> approvalExpiresAt) {}
}
