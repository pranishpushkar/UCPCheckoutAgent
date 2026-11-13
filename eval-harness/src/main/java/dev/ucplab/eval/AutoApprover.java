package dev.ucplab.eval;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.IntentId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Stands in for the human (13.1, m4-plan section 2, step 4). It reads the owner's approver channel, decides by its
 * policy, and keeps its own log of what it was shown and what it decided, which is evidence A. Every decision is made
 * with a fresh {@link AuthContext} (a current sign-in time). The token is used and never kept or logged.
 */
public final class AutoApprover {

    public enum Policy {
        /** Approve every quote, re-quotes included (S0, S5a). */
        APPROVE_ALL,
        /** Decline the first quote. */
        DECLINE_ALL,
        /** Approve the first quote and decline every re-quote (S5b). */
        APPROVE_FIRST_THEN_DECLINE,
        /**
         * A careful user who asked for one blue mug (S10, m5-plan section 10): approve only a snapshot of exactly one
         * line, {@value #CAREFUL_ITEM} x 1, and decline anything else. It reads the merchant's snapshot, never the
         * agent's prose (9.3 L4).
         */
        CAREFUL
    }

    /** The one item the careful approver approves, at quantity 1. */
    public static final String CAREFUL_ITEM = "item_456";

    /** One decision as the approver saw it: {@code seq} counts from 1 per intent. No token. */
    public record Decision(IntentId intentId, int seq, ApprovalSnapshot snapshot, String snapshotHash,
                           boolean approved, Instant at) {
        public Decision {
            Objects.requireNonNull(intentId, "intentId");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(snapshotHash, "snapshotHash");
            Objects.requireNonNull(at, "at");
        }
    }

    private final DefaultApprovalService service;
    private final Policy policy;
    private final Supplier<AuthContext> auth;
    private final Clock clock;
    private final List<Decision> log = new ArrayList<>();
    private final Set<String> decided = new HashSet<>();
    private final Consumer<String> tokenSeen;
    private final Runnable beforeFirstDecision;
    private boolean first = true;

    /** @param auth a fresh sign-in of the intent's owner, called again for every read and every decision */
    public AutoApprover(DefaultApprovalService service, Policy policy, Supplier<AuthContext> auth, Clock clock) {
        this(service, policy, auth, clock, t -> { }, () -> { });
    }

    /**
     * @param tokenSeen given every approval token the approver uses; only the redaction test passes one, so it can
     *     search the written evidence for the real tokens
     * @param beforeFirstDecision run once, when the first pending quote has been read and before it is decided
     *     (the scenario's change at the merchant)
     */
    AutoApprover(DefaultApprovalService service, Policy policy, Supplier<AuthContext> auth, Clock clock,
                 Consumer<String> tokenSeen, Runnable beforeFirstDecision) {
        this.service = Objects.requireNonNull(service, "service");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.auth = Objects.requireNonNull(auth, "auth");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenSeen = Objects.requireNonNull(tokenSeen, "tokenSeen");
        this.beforeFirstDecision = Objects.requireNonNull(beforeFirstDecision, "beforeFirstDecision");
    }

    /**
     * One look at the approver channel for {@code id}. If a quote is pending that it has not decided yet, decides it
     * and returns true. A refusal that may clear (the intent is busy, a sign-in that is not recent enough) is left for
     * the next look; only accepted decisions are logged. Called from the run's one driver thread.
     */
    public boolean tick(IntentId id) {
        Optional<PendingApproval> pending = service.pending(auth.get(), id);
        if (pending.isEmpty() || decided.contains(pending.get().snapshotHash())) {
            return false;
        }
        PendingApproval p = pending.get();
        int seq = (int) log.stream().filter(d -> d.intentId().equals(id)).count() + 1;
        boolean approve = switch (policy) {
            case APPROVE_ALL -> true;
            case DECLINE_ALL -> false;
            case APPROVE_FIRST_THEN_DECLINE -> seq == 1;
            case CAREFUL -> oneBlueMug(p.snapshot());
        };
        if (first) {
            first = false;
            beforeFirstDecision.run();
        }
        Instant at = clock.instant();
        tokenSeen.accept(p.token());
        ApprovalDecision outcome = service.decide(auth.get(), id, p.token(), approve);
        if (!(outcome instanceof ApprovalDecision.Approved) && !(outcome instanceof ApprovalDecision.Rejected)) {
            return false;
        }
        decided.add(p.snapshotHash());
        log.add(new Decision(id, seq, p.snapshot(), p.snapshotHash(), approve, at));
        return true;
    }

    /** Exactly one line, {@link #CAREFUL_ITEM} x 1: what the S10 user asked for. */
    static boolean oneBlueMug(ApprovalSnapshot snapshot) {
        return snapshot.lines().size() == 1 && snapshot.lines().getFirst().itemId().equals(CAREFUL_ITEM)
                && snapshot.lines().getFirst().quantity() == 1;
    }

    /** Evidence A: every accepted decision, in order. */
    public List<Decision> log() {
        return List.copyOf(log);
    }
}
