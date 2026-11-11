package dev.ucplab.core.approval;

import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.LeaseLostException;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StaleVersionException;
import dev.ucplab.core.intent.Transition;
import dev.ucplab.core.intent.TransitionEvent;
import dev.ucplab.core.intent.TransitionGuard;
import dev.ucplab.core.intent.TransitionTable;
import dev.ucplab.core.merchant.CheckoutView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ApprovalService} (9.2, T3, T4, I3, I10): the only code that moves an intent to APPROVED.
 *
 * <p>{@link #decide} checks, in order: the intent belongs to the caller (else NotFound, T6); the sign-in is recent
 * (else ReauthRequired, T4); the newest approval is PENDING, unexpired and the intent is AWAITING_APPROVAL (else
 * NotPending); the token matches the approval's snapshot hash in constant time (else InvalidToken). The intent write
 * is version-checked, so two concurrent decisions cannot both win; the loser gets NotPending.
 *
 * <p>A rejection moves the intent to REJECTED here; the session cancel that table 7.2 lists is made by the
 * orchestrator's next {@code advance}, so every merchant call stays in the orchestrator.
 */
public final class DefaultApprovalService implements ApprovalService {

    /** Approval TTL (9.2). */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    /** {@code maxApprovalAuthAge} (T4). */
    public static final Duration DEFAULT_MAX_AUTH_AGE = Duration.ofMinutes(15);

    public static final String APPROVAL_REJECTED = "approval_rejected";

    private final IntentRepository intents;
    private final ApprovalRepository approvals;
    private final ApprovalTokens tokens;
    private final Clock clock;
    private final IdGenerator ids;
    private final String workerId;
    private final Duration lease;
    private final Duration ttl;
    private final Duration maxAuthAge;

    /**
     * @param workerId the lease owner for the intent write; the same instance id as the orchestrator, so the
     *     orchestrator's next {@code advance} can continue without waiting for the lease to expire (7.6)
     */
    public DefaultApprovalService(
            IntentRepository intents, ApprovalRepository approvals, ApprovalTokens tokens, Clock clock,
            IdGenerator ids, String workerId, Duration lease, Duration ttl, Duration maxAuthAge) {
        this.intents = Objects.requireNonNull(intents, "intents");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.maxAuthAge = Objects.requireNonNull(maxAuthAge, "maxAuthAge");
    }

    @Override
    public PendingApproval open(PurchaseIntent intent, CheckoutView quote) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(quote, "quote");
        Instant now = clock.instant();
        // An open that crashed before the intent moved leaves a PENDING approval; the new one replaces it.
        for (ApprovalRecord old : approvals.listForSystem(intent.id())) {
            if (old.status() == ApprovalStatus.PENDING) {
                approvals.transition(old.id(), ApprovalStatus.PENDING, ApprovalStatus.EXPIRED, now, Optional.empty());
            }
        }
        ApprovalSnapshot snapshot = new ApprovalSnapshot(
                intent.id(), intent.merchantId(), quote.id(),
                quote.lines().stream().map(l -> new SnapshotLine(l.itemId(), l.quantity(), l.unitPriceMinor())).toList(),
                quote.currency(), quote.totalMinor(), quote.totalMinor(), now.plus(ttl));
        String hash = snapshot.hash();
        String approvalId = ids.next();
        String token = tokens.mint(approvalId, intent.id(), hash);
        approvals.insert(new ApprovalRecord(approvalId, intent.id(), intent.owner(), snapshot, hash,
                ApprovalTokens.hashForStorage(token), snapshot.expiresAt(), ApprovalStatus.PENDING, Optional.empty(),
                Optional.empty()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approval_id", approvalId);
        payload.put("snapshot_hash", hash);
        payload.put("total_minor", snapshot.totalMinor());
        payload.put("max_total_minor", snapshot.maxTotalMinor());
        intents.appendEvent(intent.id(), IntentEvent.of("approval_opened", payload, now));
        return new PendingApproval(snapshot, hash, token);
    }

    /**
     * The pending approval for the owner's approver channel ({@code GET /api/intents/{id}/approval}, 23.2). The token
     * is not stored, so it is re-minted from the snapshot hash. Empty for anyone but the owner (T6) or when nothing is
     * pending.
     */
    public Optional<PendingApproval> pending(AuthContext auth, IntentId id) {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(id, "id");
        if (intents.find(auth.user(), id).isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return approvals.findLatest(auth.user(), id)
                .filter(a -> a.status() == ApprovalStatus.PENDING && now.isBefore(a.expiresAt()))
                .map(a -> new PendingApproval(a.snapshot(), a.snapshotHash(), tokens.mint(a.id(), id, a.snapshotHash())));
    }

    @Override
    public ApprovalDecision decide(AuthContext auth, IntentId id, String token, boolean approve) {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(token, "token");

        if (intents.find(auth.user(), id).isEmpty()) {
            return new ApprovalDecision.NotFound();
        }
        if (!auth.isRecent(clock, maxAuthAge)) {
            return new ApprovalDecision.ReauthRequired();
        }
        Optional<ApprovalRecord> latest = approvals.findLatest(auth.user(), id);
        if (latest.isEmpty() || latest.get().status() != ApprovalStatus.PENDING) {
            return new ApprovalDecision.NotPending();
        }
        ApprovalRecord approval = latest.get();
        Instant now = clock.instant();
        if (!now.isBefore(approval.expiresAt())) {
            return new ApprovalDecision.NotPending();
        }
        if (!tokens.verify(token, approval.id(), id, approval.snapshotHash())) {
            return new ApprovalDecision.InvalidToken();
        }

        PurchaseIntent intent = intents.claim(id, workerId, lease)
                .orElseThrow(() -> new LeaseLostException(id, workerId));
        if (intent.state() != IntentState.AWAITING_APPROVAL) {
            return new ApprovalDecision.NotPending();
        }
        Transition t = approve
                ? TransitionTable.resolve(intent.state(), TransitionEvent.APPROVED, Set.of(TransitionGuard.APPROVAL_VALID))
                : TransitionTable.resolve(intent.state(), TransitionEvent.REJECTED, Set.of());
        PurchaseIntent next = intent.moved(t, now);
        next = approve
                ? next.withApprovedSnapshotHash(Optional.of(approval.snapshotHash()))
                : next.withFailureReason(APPROVAL_REJECTED);
        try {
            intents.update(next, intent.version(), workerId);
        } catch (StaleVersionException e) {
            return new ApprovalDecision.NotPending();
        }
        // The intent write above decided the race; this records the decision on the approval (I10 decided_by).
        approvals.transition(approval.id(), ApprovalStatus.PENDING,
                approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED, now, Optional.of(auth.user()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approval_id", approval.id());
        payload.put("decision", approve ? "approve" : "reject");
        payload.put("decided_by", auth.user().value());
        intents.appendEvent(id, IntentEvent.of("approval_decided", payload, now));
        intents.appendEvent(id, transitionEvent(t, now));
        return approve ? new ApprovalDecision.Approved(id) : new ApprovalDecision.Rejected(id);
    }

    private static IntentEvent transitionEvent(Transition t, Instant at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", t.from().name());
        payload.put("event", t.event().name());
        payload.put("guard", t.guard().name());
        payload.put("to", t.to().name());
        return IntentEvent.of("transition", payload, at);
    }
}
