package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.AWAITING_APPROVAL;
import static dev.ucplab.core.intent.IntentState.COMPLETING;
import static dev.ucplab.core.intent.IntentState.PROPOSED;
import static dev.ucplab.core.intent.IntentState.QUOTING;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.approval.ApprovalService;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.crypto.Sha256;
import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.AttemptId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.DuplicateIntentException;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentNotFoundException;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.intent.Transition;
import dev.ucplab.core.intent.TransitionEvent;
import dev.ucplab.core.intent.TransitionGuard;
import dev.ucplab.core.intent.TransitionTable;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.Buyer;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.NoGatewayException;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.QuoteShape;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import dev.ucplab.core.policy.PolicyDecision;
import dev.ucplab.core.policy.PolicyEngine;
import dev.ucplab.core.policy.RequoteOutcome;
import dev.ucplab.core.policy.RequotePolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * The purchase orchestrator (section 7): table 7.2 driven by a step function with a write-ahead attempt log.
 *
 * <p><b>One step</b> ({@link #advance}, 7.3): take or renew the lease, load the intent and its attempts, decide, make
 * at most one merchant call, persist. A call is preceded by {@code recordAttempt} (attempt SENT and intent v+1 in one
 * write) and followed by the fenced {@code resolveAttempt} that resolves the attempt and moves the state together.
 * When that write fails on fencing (7.6) the step throws and the attempt stays SENT, which recovery treats as uncertain.
 *
 * <p><b>Reading the attempt log after a crash.</b> In COMPLETING, the newest {@code complete} attempt under the
 * current key tells the step what happened: SENT means the outcome of a call is unknown (crash between 7.3 steps 3 and
 * 5), so the intent goes to RECONCILING; UNCERTAIN means reconciliation approved a resend (table 7.2, RECONCILING
 * observed ready_for_complete). Only the process that made that observation makes the resend, with the stored key and
 * body (I7): it remembers the approval in memory, keyed by the intent's version. A step that finds COMPLETING with an
 * UNCERTAIN attempt and no such memory (a restart, or a worker that took over) sends nothing and goes back to
 * RECONCILING, so a resend always follows a read that process made itself (7.6). The two attempt states cannot be
 * confused because an uncertain outcome is written together with the move to RECONCILING.
 *
 * <p><b>Keys.</b> create and cancel use revision 0; the update for re-quote n uses revision n-1, and the
 * {@code complete} key uses the requote_count at approval (7.4). A new {@code complete} key is sent only when every
 * earlier {@code complete} attempt is RESPONDED: either with a no-charge outcome, or resolved by a reconciliation
 * read that observed the session incomplete (the 7.4 guard). A definitive answer to a resend also resolves the
 * earlier attempts under its key, because the merchant keeps one result per key (A.1).
 *
 * <p><b>Side-effect cancels.</b> Table 7.2 rows that end in REJECTED or FAILED_NOT_CHARGED with a cancel are
 * followed by one best-effort cancel, made by the next {@code advance} of the now terminal intent so a step still
 * makes at most one call.
 *
 * <p><b>Who waits</b> (7.5). {@code advance} never sleeps. After a step that leaves the intent RECONCILING it returns
 * {@code nextAttemptAt} for the next poll; after one that approves a resend, {@code nextAttemptAt} is now. The caller
 * waits and calls {@code advance} again.
 *
 * <p>No locks are held across merchant calls; all coordination is the repository's version and lease checks (7.8).
 */
public final class DefaultOrchestrator implements Orchestrator {

    static final String OUT_OF_STOCK = "out_of_stock";
    static final String ITEM_UNAVAILABLE = "item_unavailable";
    static final String PRICE_CHANGED = "price_changed";
    static final String PAYMENT_DECLINED = "payment_declined";
    static final String PAYMENT_INVALID = "payment_invalid";
    static final String QUOTE_RETRIES_EXHAUSTED = "quote_retries_exhausted";
    static final String CREATE_REJECTED_REASON = "create_rejected";
    static final String QUANTITY_ADJUSTED = "quantity_adjusted";
    static final String QUOTE_LINES_DIFFER = "quote_lines_differ";
    static final String APPROVAL_EXPIRED = "approval_expired";
    static final String CANCELED_BY_MERCHANT = "canceled_by_merchant";
    static final String OUTCOME_UNKNOWN = "outcome_unknown";
    static final String APPROVAL_INVALID = "approval_invalid";
    static final String POLICY_PREFIX = "policy_";
    static final String MERCHANT_ERROR_PREFIX = "merchant_error_";
    static final String COMPLETE_ERROR_PREFIX = "complete_error:";

    /** failure_reason values that name a table 7.2 cancel row; used to give a COMPLETING error code its reason. */
    private static final Set<String> CANCEL_OWED = Set.of(OUT_OF_STOCK, ITEM_UNAVAILABLE, QUANTITY_ADJUSTED,
            QUOTE_LINES_DIFFER, PAYMENT_DECLINED,
            RequotePolicy.PRICE_UNSTABLE, DefaultApprovalService.APPROVAL_REJECTED, APPROVAL_EXPIRED);

    /** Cancel attempts for one intent before the worker gives up (7.6). */
    static final int MAX_CANCEL_ATTEMPTS = 3;

    /** Probe creates for one orphaned session before the worker gives up (7.6 M3 note). */
    static final int MAX_PROBE_ATTEMPTS = 3;

    private static final int MAX_RATIONALE = 300;
    private static final System.Logger LOG = System.getLogger(DefaultOrchestrator.class.getName());

    private final IntentRepository intents;
    private final ApprovalRepository approvals;
    private final ApprovalService approvalService;
    private final Function<MerchantId, MerchantGateway> gateways;
    private final PolicyEngine policy;
    private final RequotePolicy requotePolicy;
    private final PaymentInstrument payment;
    private final OrchestratorConfig config;
    private final Clock clock;
    private final IdGenerator ids;
    private final String workerId;
    private final RandomGenerator jitter;
    private final ReentrantLock jitterLock = new ReentrantLock();
    /**
     * Resends this process approved after a {@code get} showed ready_for_complete (7.5), by intent: the version the
     * intent has once the approving write is stored. Only the process that observed the status continues to the resend;
     * after a restart or a takeover the map is empty, so the new worker reads again first (7.6).
     */
    private final Map<IntentId, Long> approvedResends = new ConcurrentHashMap<>();

    /**
     * @param payment the instrument put in every frozen {@code complete} body (7.4); one mock card in M1
     * @param workerId this instance's lease owner id (7.6)
     * @param jitter a seeded random source for poll jitter (7.5); guarded here, so it need not be thread-safe
     */
    public DefaultOrchestrator(
            IntentRepository intents, ApprovalRepository approvals, ApprovalService approvalService,
            MerchantGateway merchant, PolicyEngine policy, RequotePolicy requotePolicy, PaymentInstrument payment,
            OrchestratorConfig config, Clock clock, IdGenerator ids, String workerId, RandomGenerator jitter) {
        this(intents, approvals, approvalService, onlyGateway(merchant), policy, requotePolicy, payment, config, clock,
                ids, workerId, jitter);
    }

    /** One gateway for every merchant: a deployment with a single merchant (M1 to M6). */
    private static Function<MerchantId, MerchantGateway> onlyGateway(MerchantGateway merchant) {
        Objects.requireNonNull(merchant, "merchant");
        return id -> merchant;
    }

    /**
     * @param gateways the gateway of an intent's merchant (8.5: one gateway per merchant, I11), for example
     *     {@link dev.ucplab.core.merchant.MerchantGateways#of}. Every merchant call of an intent goes through the gateway
     *     of {@code intent.merchantId()} and no other. A merchant with none throws {@link NoGatewayException} before
     *     anything is written or sent.
     */
    public DefaultOrchestrator(
            IntentRepository intents, ApprovalRepository approvals, ApprovalService approvalService,
            Function<MerchantId, MerchantGateway> gateways, PolicyEngine policy, RequotePolicy requotePolicy,
            PaymentInstrument payment, OrchestratorConfig config, Clock clock, IdGenerator ids, String workerId,
            RandomGenerator jitter) {
        this.intents = Objects.requireNonNull(intents, "intents");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.approvalService = Objects.requireNonNull(approvalService, "approvalService");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.requotePolicy = Objects.requireNonNull(requotePolicy, "requotePolicy");
        this.payment = Objects.requireNonNull(payment, "payment");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    // ---------------------------------------------------------------- Orchestrator

    /**
     * Creates the intent and obtains the quote (6.2). The policy decision is made before the insert, so the intent is
     * stored already in QUOTING or REJECTED: a crash can never leave a PROPOSED intent that no worker would pick up.
     * An identical active proposal returns the existing intent (7.7).
     */
    @Override
    public ProposalResult propose(AuthContext auth, MerchantId merchantId, List<ProposedLine> lines, String rationale) {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(rationale, "rationale");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("a proposal needs at least one line");
        }
        String dedupeKey = dedupeKey(auth.user(), merchantId, lines);
        Optional<ProposalResult> existing = existingProposal(auth.user(), dedupeKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = clock.instant();
        PolicyDecision decision = policy.evaluateProposal(auth.user(), merchantId, lines);
        Transition t = decision.allowed()
                ? TransitionTable.resolve(PROPOSED, TransitionEvent.POLICY_OK, Set.of(TransitionGuard.POLICY_ALLOWS))
                : TransitionTable.resolve(PROPOSED, TransitionEvent.POLICY_DENIED, Set.of());
        PurchaseIntent intent = PurchaseIntent.proposed(
                        new IntentId(ids.next()), auth.user(), merchantId, dedupeKey, lines, auth.email(), now)
                .withState(t.to(), now)
                .withLease(Optional.of(workerId), Optional.of(now.plus(config.lease())));
        if (!decision.allowed()) {
            intent = intent.withFailureReason(policyReason(decision));
        }
        try {
            intents.insert(intent);
        } catch (DuplicateIntentException e) {
            return existingProposal(auth.user(), dedupeKey).orElseThrow(() -> e);
        }
        Map<String, Object> proposed = new LinkedHashMap<>();
        proposed.put("merchant_id", merchantId.value());
        proposed.put("lines", lines.stream().map(l -> Map.of("item_id", l.itemId(), "quantity", l.quantity())).toList());
        proposed.put("rationale", rationale.length() > MAX_RATIONALE ? rationale.substring(0, MAX_RATIONALE) : rationale);
        event(intent.id(), "proposed", proposed);
        policyEvent(intent.id(), "proposal", decision);
        transitionEvent(intent.id(), t);
        if (!decision.allowed()) {
            return new ProposalResult(intent.id(), intent.state(), Optional.empty());
        }

        // Quote now: one create call. Retries live in the gateway (7.2, 8.2); this never loops.
        Step last = step(intent.id());
        return new ProposalResult(intent.id(), last.after(),
                last.after() == AWAITING_APPROVAL ? last.quote() : Optional.empty());
    }

    @Override
    public StepResult advance(IntentId id) {
        Objects.requireNonNull(id, "id");
        Step s = step(id);
        return new StepResult(id, s.before(), s.after(), s.idle(), s.idle() ? Optional.empty() : nextAttemptAt(id));
    }

    /**
     * 7.5 "Who waits". RECONCILING: the next poll. The delay grows with the time since uncertain_since (so it starts at
     * pollInitial, roughly doubles per poll, and is not reset by a restart), is capped at pollMax, gets the configured
     * jitter, and never passes the deadline. COMPLETING after a step: a resend approved by reconciliation, due now.
     */
    private Optional<Instant> nextAttemptAt(IntentId id) {
        PurchaseIntent intent = intents.findForSystem(id)
                .orElseThrow(() -> new IntentNotFoundException("no such intent: " + id));
        Instant now = clock.instant();
        return switch (intent.state()) {
            case COMPLETING -> Optional.of(now);
            case RECONCILING -> {
                Instant since = intent.uncertainSince().orElse(now);
                Duration elapsed = Duration.between(since, now);
                Duration base = elapsed.compareTo(config.pollInitial()) < 0 ? config.pollInitial()
                        : elapsed.compareTo(config.pollMax()) > 0 ? config.pollMax() : elapsed;
                double factor;
                jitterLock.lock();
                try {
                    factor = 1 + config.pollJitter() * (2 * jitter.nextDouble() - 1);
                } finally {
                    jitterLock.unlock();
                }
                Instant next = now.plusNanos(Math.round(base.toNanos() * factor));
                Instant deadline = since.plus(config.reconcileDeadline());
                yield Optional.of(next.isAfter(deadline) && !now.isAfter(deadline) ? deadline : next);
            }
            default -> Optional.empty();
        };
    }

    @Override
    public IntentStatus status(AuthContext auth, IntentId id) {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(id, "id");
        return intents.find(auth.user(), id)
                .map(i -> new IntentStatus(i.id(), i.state(), i.orderId(), i.failureReason()))
                .orElseThrow(() -> new IntentNotFoundException("no such intent: " + id));
    }

    /** {@code dedupe_key = sha256(userId + '|' + merchantId + '|' + sorted(itemId:quantity))} (7.7), comma-joined. */
    public static String dedupeKey(UserId user, MerchantId merchantId, List<ProposedLine> lines) {
        String items = lines.stream().map(l -> l.itemId() + ':' + l.quantity()).sorted()
                .collect(Collectors.joining(","));
        return Sha256.hex(user.value() + '|' + merchantId.value() + '|' + items);
    }

    // ---------------------------------------------------------------- the step function (7.3)

    private record Step(IntentState before, IntentState after, boolean idle, Optional<CheckoutView> quote) {}

    private record Move(PurchaseIntent next, Transition transition) {}

    private Step step(IntentId id) {
        Optional<PurchaseIntent> claimed = intents.claim(id, workerId, config.lease());
        if (claimed.isEmpty()) {
            PurchaseIntent current = intents.findForSystem(id)
                    .orElseThrow(() -> new IntentNotFoundException("no such intent: " + id));
            return idle(current);
        }
        PurchaseIntent intent = claimed.get();
        List<MerchantAttempt> attempts = intents.attempts(id);
        return switch (intent.state()) {
            case PROPOSED -> applyProposalPolicy(intent);
            case QUOTING -> quote(intent, attempts);
            case AWAITING_APPROVAL -> expireApproval(intent);
            case APPROVED -> sendComplete(intent, attempts);
            case COMPLETING -> completing(intent, attempts);
            case RECONCILING -> reconcile(intent, attempts);
            case REJECTED, FAILED_NOT_CHARGED -> cancelIfOwed(intent, attempts);
            case COMPLETED, ESCALATED -> idle(intent);
        };
    }

    /** PROPOSED is never stored by {@link #propose}; this handles an intent inserted in PROPOSED by other code. */
    private Step applyProposalPolicy(PurchaseIntent intent) {
        PolicyDecision decision = policy.evaluateProposal(intent.owner(), intent.merchantId(), intent.lines());
        Move m = decision.allowed()
                ? move(intent, TransitionEvent.POLICY_OK, TransitionGuard.POLICY_ALLOWS)
                : move(intent, TransitionEvent.POLICY_DENIED);
        PurchaseIntent next = decision.allowed() ? m.next() : m.next().withFailureReason(policyReason(decision));
        intents.update(next, intent.version(), workerId);
        policyEvent(intent.id(), "proposal", decision);
        transitionEvent(intent.id(), m.transition());
        return moved(intent, next);
    }

    /**
     * The gateway of the intent's merchant (I11, 8.5), or {@link NoGatewayException}. Called before the first write of
     * every step that calls a merchant, so a missing gateway sends nothing and changes nothing: the intent keeps its
     * state (RecoveryWorker logs the failed step and tries again next pass), and never falls back to another merchant.
     */
    private MerchantGateway gateway(PurchaseIntent intent) {
        MerchantGateway gateway;
        try {
            gateway = gateways.apply(intent.merchantId());
        } catch (NoGatewayException e) {
            LOG.log(System.Logger.Level.ERROR, "no gateway for merchant {0}", intent.merchantId());
            throw e;
        }
        if (gateway == null) {
            LOG.log(System.Logger.Level.ERROR, "no gateway for merchant {0}", intent.merchantId());
            throw new NoGatewayException(intent.merchantId());
        }
        return gateway;
    }

    // ---------------------------------------------------------------- QUOTING

    private Step quote(PurchaseIntent intent, List<MerchantAttempt> attempts) {
        boolean create = intent.requoteCount() == 0;
        AttemptOp op = create ? AttemptOp.CREATE : AttemptOp.UPDATE;
        int revision = create ? 0 : intent.requoteCount() - 1;
        // Retries live in the gateway (7.2, 8.2): an attempt already under this key (answered with a failure, or left
        // SENT by a crash) is not repeated here.
        boolean tried = attempts.stream().anyMatch(a -> a.op() == op && a.revision() == revision);
        if (tried) {
            Move m = move(intent, TransitionEvent.RETRIES_EXHAUSTED);
            // A create left SENT may have made a session the intent never learned of: the probe finds it (7.6 M3 note).
            boolean orphanPossible = create && attempts.stream()
                    .anyMatch(a -> a.op() == AttemptOp.CREATE && a.revision() == 0 && mayHaveCreatedSession(a));
            PurchaseIntent next = m.next().withFailureReason(QUOTE_RETRIES_EXHAUSTED)
                    .withCancelPending(m.next().cancelPending() || (orphanPossible && intent.sessionId().isEmpty()));
            intents.update(next, intent.version(), workerId);
            transitionEvent(intent.id(), m.transition());
            return moved(intent, next);
        }

        // Before any write: a merchant without a gateway leaves the intent exactly as it is.
        MerchantGateway merchant = gateway(intent);
        IdempotencyKey key = IdempotencyKey.derive(intent.id(), create ? MutatingOp.CREATE : MutatingOp.UPDATE, revision);
        String body;
        Supplier<CallOutcome<CheckoutView>> call;
        if (create) {
            CreateCheckout request = new CreateCheckout(intent.lines(), Optional.of(intent.buyerEmail()));
            body = CheckoutWire.createBody(request);
            call = () -> merchant.create(request, key);
        } else {
            SessionId session = requireSession(intent);
            // An update replaces the session, so it resends the line ids and buyer fields of the last response this
            // intent stored (8.4). Nothing is fetched: one merchant call per step.
            UpdateCheckout request = UpdateCheckout.from(intent.lines(), Buyer.ofEmail(intent.buyerEmail()),
                    lastQuoteShape(attempts));
            body = CheckoutWire.updateBody(request);
            call = () -> merchant.update(session, request, key);
        }
        MerchantAttempt attempt = newAttempt(intent.id(), op, revision, key, Optional.of(new FrozenBody(body)));
        PurchaseIntent written = writeAhead(intent, attempt, intent.withState(QUOTING, clock.instant()));
        return onQuoteOutcome(written, attempt, call.get(), create);
    }

    private Step onQuoteOutcome(
            PurchaseIntent written, MerchantAttempt attempt, CallOutcome<CheckoutView> outcome, boolean create) {
        return switch (outcome) {
            case CallOutcome.Ok<CheckoutView> ok -> {
                CheckoutView view = ok.value();
                Optional<Integer> http = Optional.of(ok.httpStatus());
                Optional<String> summary = Optional.of(CheckoutWire.responseSummary(view));
                if (!create && !view.id().equals(requireSession(written))) {
                    throw new IllegalStateException("I4: update answered for another session: " + view.id());
                }
                if (view.status() == CheckoutStatus.COMPLETED || view.status() == CheckoutStatus.COMPLETE_IN_PROGRESS) {
                    // Defensive row (7.2): a late complete landed, or is charging, after the re-quote began. Send
                    // nothing new; reconciliation polls and records the order.
                    Move m = move(written, view.status() == CheckoutStatus.COMPLETED
                            ? TransitionEvent.OBSERVED_COMPLETED
                            : TransitionEvent.OBSERVED_COMPLETE_IN_PROGRESS);
                    PurchaseIntent next = withUncertainSince(m.next());
                    resolve(attempt, AttemptStatus.RESPONDED, http, summary, next, written.version());
                    transitionEvent(written.id(), m.transition());
                    yield moved(written, next);
                }
                PurchaseIntent base = create ? written.withSessionId(view.id()) : written;
                Set<String> codes = codes(view);
                if (codes.contains(OUT_OF_STOCK)) {
                    yield finish(base, attempt, AttemptStatus.RESPONDED, http, summary,
                            move(base, TransitionEvent.OUT_OF_STOCK), OUT_OF_STOCK);
                }
                if (codes.contains(ITEM_UNAVAILABLE)) {
                    yield finish(base, attempt, AttemptStatus.RESPONDED, http, summary,
                            move(base, TransitionEvent.ITEM_UNAVAILABLE), ITEM_UNAVAILABLE);
                }
                if (!sameBasket(base, view)) {
                    // I2, Q2: no partial approval. A quote that is not the basket the user proposed (the merchant cut
                    // a quantity, with or without a quantity_adjusted warning) is refused; re-propose instead.
                    // Uses the QUOTING out_of_stock row (FAILED_NOT_CHARGED, cancel the session).
                    yield finish(base, attempt, AttemptStatus.RESPONDED, http, summary,
                            move(base, TransitionEvent.OUT_OF_STOCK),
                            codes.contains(QUANTITY_ADJUSTED) ? QUANTITY_ADJUSTED : QUOTE_LINES_DIFFER);
                }
                if (view.status() == CheckoutStatus.READY_FOR_COMPLETE) {
                    yield quoteReady(base, attempt, http, summary, view);
                }
                // An answer that is neither a quote nor a known failure: retry under the same key until exhausted.
                yield exhaustQuoting(base, attempt, AttemptStatus.RESPONDED, http, summary);
            }
            case CallOutcome.Rejected<CheckoutView> r when create -> finish(written, attempt, AttemptStatus.RESPONDED,
                    Optional.of(r.httpStatus()), Optional.of(describe(r)), move(written, TransitionEvent.CREATE_REJECTED),
                    rejectedReason(r));
            // Rejected is valid only for create (8.1). On an update it cannot settle anything, so it is retried
            // like an answer that is neither a quote nor a known failure.
            case CallOutcome.Rejected<CheckoutView> r -> exhaustQuoting(written, attempt, AttemptStatus.RESPONDED,
                    Optional.of(r.httpStatus()), Optional.of(describe(r)));
            case CallOutcome.ProtocolError<CheckoutView> e when isRetryable(e) -> exhaustQuoting(written, attempt,
                    AttemptStatus.RESPONDED, Optional.of(e.httpStatus()), Optional.of(describe(e)));
            case CallOutcome.ProtocolError<CheckoutView> e -> {
                Optional<Integer> http = Optional.of(e.httpStatus());
                Optional<String> response = Optional.of(describe(e));
                if (OUT_OF_STOCK.equals(e.code())) {
                    // Kept for a merchant that reports unavailability as a protocol error; the gateway reports a
                    // create with every item unavailable as Rejected (8.1).
                    yield finish(written, attempt, AttemptStatus.RESPONDED, http, response,
                            move(written, TransitionEvent.OUT_OF_STOCK), OUT_OF_STOCK);
                }
                if (ITEM_UNAVAILABLE.equals(e.code())) {
                    yield finish(written, attempt, AttemptStatus.RESPONDED, http, response,
                            move(written, TransitionEvent.ITEM_UNAVAILABLE), ITEM_UNAVAILABLE);
                }
                // DESIGN GAP: no row for a non-retryable protocol error while quoting. No money can have moved, so
                // this uses the QUOTING -> FAILED_NOT_CHARGED row that has no side effect.
                yield finish(written, attempt, AttemptStatus.RESPONDED, http, response,
                        move(written, TransitionEvent.RETRIES_EXHAUSTED), MERCHANT_ERROR_PREFIX + e.code());
            }
            case CallOutcome.NotSent<CheckoutView> n -> exhaustQuoting(written, attempt, AttemptStatus.UNCERTAIN,
                    Optional.empty(), Optional.of(describe(n)));
            case CallOutcome.Uncertain<CheckoutView> u -> create && written.sessionId().isEmpty()
                    // The request may have reached the merchant and made a session: owe the probe (7.6 M3 note).
                    ? finish(written, attempt, AttemptStatus.UNCERTAIN, Optional.empty(), Optional.of(describe(u)),
                            move(written, TransitionEvent.RETRIES_EXHAUSTED), QUOTE_RETRIES_EXHAUSTED, true)
                    : exhaustQuoting(written, attempt, AttemptStatus.UNCERTAIN, Optional.empty(),
                            Optional.of(describe(u)));
        };
    }

    /**
     * Whether a create attempt may have left a session at the merchant: it is still SENT (a crash), or it is UNCERTAIN
     * after an answer other than NotSent, so the request may have left. A NotSent answer proves it never did (8.1), and
     * a RESPONDED attempt was answered, so neither can have left a session the intent does not know.
     */
    private static boolean mayHaveCreatedSession(MerchantAttempt create) {
        return switch (create.status()) {
            case SENT -> true;
            case UNCERTAIN -> create.responseJson()
                    .map(r -> !"not_sent".equals(CanonicalJson.readObject(r).get("outcome"))).orElse(true);
            case RESPONDED -> false;
        };
    }

    private Step quoteReady(
            PurchaseIntent base, MerchantAttempt attempt, Optional<Integer> http, Optional<String> summary,
            CheckoutView view) {
        PolicyDecision decision = policy.evaluateQuote(base.owner(), view);
        if (!decision.allowed()) {
            // DESIGN GAP: table 7.2 has no QUOTING row for a quote that policy denies (P2, P3, P4). This uses the
            // QUOTING -> FAILED_NOT_CHARGED row with a best-effort cancel; failure_reason names the rule.
            policyEvent(base.id(), "quote", decision);
            return finish(base, attempt, AttemptStatus.RESPONDED, http, summary,
                    move(base, TransitionEvent.ITEM_UNAVAILABLE), policyReason(decision));
        }
        Optional<RequoteOutcome> p7 = Optional.empty();
        if (base.requoteCount() > 0) {
            long approvedTotal = approvals.findLatestForSystem(base.id())
                    .map(a -> a.snapshot().totalMinor()).orElse(view.totalMinor());
            p7 = Optional.of(requotePolicy.evaluateReapproval(base.requoteCount() - 1, approvedTotal, view.totalMinor()));
        }
        Move m = move(base, TransitionEvent.QUOTE_READY, TransitionGuard.QUOTE_POLICY_ALLOWS);
        // Opened before the state write: a crash in between leaves a PENDING approval that the next open replaces,
        // never an AWAITING_APPROVAL intent without an approval.
        approvalService.open(m.next(), view);
        resolve(attempt, AttemptStatus.RESPONDED, http, summary, m.next(), base.version());
        policyEvent(base.id(), "quote", decision);
        p7.ifPresent(r -> event(base.id(), "policy_decision", Map.of(
                "stage", "requote", "rule", r.rule().name(), "allowed", true, "reason", r.reason())));
        transitionEvent(base.id(), m.transition());
        return new Step(QUOTING, m.next().state(), false, Optional.of(view));
    }

    /**
     * Ends QUOTING after a failed or unusable answer (7.2, retries exhausted). Retries live in the gateway (8.2); the
     * orchestrator never repeats a QUOTING call, so the first such answer is the last.
     */
    private Step exhaustQuoting(
            PurchaseIntent base, MerchantAttempt attempt, AttemptStatus status, Optional<Integer> http,
            Optional<String> response) {
        return finish(base, attempt, status, http, response, move(base, TransitionEvent.RETRIES_EXHAUSTED),
                QUOTE_RETRIES_EXHAUSTED);
    }

    /** Resolves the attempt and moves to a terminal state with a failure reason, in one write. */
    private Step finish(
            PurchaseIntent base, MerchantAttempt attempt, AttemptStatus status, Optional<Integer> http,
            Optional<String> response, Move m, String failureReason) {
        return finish(base, attempt, status, http, response, m, failureReason, false);
    }

    /** As above; {@code cancelOwed} also sets cancel_pending, in the same write, for a session that may exist unseen. */
    private Step finish(
            PurchaseIntent base, MerchantAttempt attempt, AttemptStatus status, Optional<Integer> http,
            Optional<String> response, Move m, String failureReason, boolean cancelOwed) {
        PurchaseIntent next = m.next().withFailureReason(failureReason);
        next = cancelOwed ? next.withCancelPending(true) : next;
        resolve(attempt, status, http, response, next, base.version());
        transitionEvent(base.id(), m.transition());
        return moved(base, next);
    }

    // ---------------------------------------------------------------- AWAITING_APPROVAL

    /**
     * Approve and reject are decided by ApprovalService (9.2); a step here only expires a stale approval. The intent is
     * written first (fenced by version and lease, like {@code decide}) and the approval second, so a crash between the
     * two leaves a terminal intent with a PENDING approval, never an AWAITING_APPROVAL intent that no worker would pick
     * up again (I5, 7.6). An approval already EXPIRED with the intent still AWAITING_APPROVAL, which only an earlier
     * write order could leave behind, is finished the same way.
     */
    private Step expireApproval(PurchaseIntent intent) {
        Instant now = clock.instant();
        Optional<ApprovalRecord> latest = approvals.findLatestForSystem(intent.id());
        if (latest.isEmpty()) {
            return idle(intent);
        }
        ApprovalStatus status = latest.get().status();
        boolean due = status == ApprovalStatus.EXPIRED
                || (status == ApprovalStatus.PENDING && !now.isBefore(latest.get().expiresAt()));
        if (!due) {
            return idle(intent);
        }
        Move m = move(intent, TransitionEvent.EXPIRED);
        PurchaseIntent next = m.next().withFailureReason(APPROVAL_EXPIRED);
        intents.update(next, intent.version(), workerId);
        approvals.transition(latest.get().id(), ApprovalStatus.PENDING, ApprovalStatus.EXPIRED, now, Optional.empty());
        transitionEvent(intent.id(), m.transition());
        return moved(intent, next);
    }

    // ---------------------------------------------------------------- APPROVED -> COMPLETING

    private Step sendComplete(PurchaseIntent intent, List<MerchantAttempt> attempts) {
        // I3, checked before the write-ahead so a refusal leaves no attempt behind. PENDING is accepted for the case
        // where decide moved the intent and crashed before recording the decision.
        Optional<String> hash = intent.approvedSnapshotHash();
        Optional<ApprovalRecord> latest = approvals.findLatestForSystem(intent.id());
        Optional<ApprovalRecord> usable = latest
                .filter(a -> hash.isPresent() && a.snapshotHash().equals(hash.get()))
                // I3, I4: the approved snapshot is for this intent's session.
                .filter(a -> intent.sessionId().equals(Optional.of(a.snapshot().sessionId())))
                .filter(a -> Set.of(ApprovalStatus.PENDING, ApprovalStatus.APPROVED, ApprovalStatus.CONSUMED)
                        .contains(a.status()));
        if (usable.isEmpty()) {
            return escalateInvalidApproval(intent, attempts, latest, hash);
        }
        ApprovalRecord approval = usable.get();
        SessionId session = requireSession(intent);
        int revision = intent.requoteCount();
        List<MerchantAttempt> completes = completes(attempts);
        if (completes.stream().anyMatch(a -> a.revision() >= revision)) {
            throw new IllegalStateException("7.4: a complete already exists under revision " + revision);
        }
        if (completes.stream().anyMatch(a -> a.status() != AttemptStatus.RESPONDED)) {
            throw new IllegalStateException("7.4 new-key guard: an earlier complete attempt is unresolved: "
                    + intent.id());
        }

        MerchantGateway merchant = gateway(intent);
        FrozenBody body = CheckoutWire.completeBody(payment);
        IdempotencyKey key = IdempotencyKey.derive(intent.id(), MutatingOp.COMPLETE, revision);
        MerchantAttempt attempt = newAttempt(intent.id(), AttemptOp.COMPLETE, revision, key, Optional.of(body));
        Move m = move(intent, TransitionEvent.SEND_COMPLETE, TransitionGuard.ATTEMPT_PERSISTED);
        // A new key starts a fresh reconciliation deadline (7.5 rule 1); cleared in the same write as the attempt.
        PurchaseIntent written = writeAhead(intent, attempt, m.next().withoutUncertainSince());
        // Both approval writes come after the fenced write-ahead and before the request leaves (7.6): only the worker
        // that holds the lease repairs the approval. A PENDING approval under an APPROVED intent means a crash inside
        // decide; the decision was the owner's, because only the owner can approve (I10).
        if (approval.status() == ApprovalStatus.PENDING) {
            approvals.transition(approval.id(), ApprovalStatus.PENDING, ApprovalStatus.APPROVED, clock.instant(),
                    Optional.of(intent.owner()));
            event(intent.id(), "approval_decision_recovered", Map.of("approval_id", approval.id()));
        }
        if (approval.status() != ApprovalStatus.CONSUMED) {
            approvals.transition(approval.id(), ApprovalStatus.APPROVED, ApprovalStatus.CONSUMED, clock.instant(),
                    Optional.empty());
        }
        transitionEvent(intent.id(), m.transition());
        return onCompleteOutcome(written, attempt, merchant.complete(session, body, key));
    }

    private Step onCompleteOutcome(PurchaseIntent written, MerchantAttempt attempt, CallOutcome<CheckoutView> outcome) {
        if (outcome instanceof CallOutcome.Ok<CheckoutView> ok) {
            CheckoutView view = ok.value();
            Optional<Integer> http = Optional.of(ok.httpStatus());
            Optional<String> summary = Optional.of(CheckoutWire.responseSummary(view));
            Set<String> codes = codes(view);
            if (view.status() == CheckoutStatus.COMPLETED && view.order().isPresent()) {
                Move m = move(written, TransitionEvent.MERCHANT_COMPLETED);
                PurchaseIntent next = m.next().withOrderId(view.order().get().id());
                settleSiblings(written, attempt, "completed");
                resolve(attempt, AttemptStatus.RESPONDED, http, summary, next, written.version());
                transitionEvent(written.id(), m.transition());
                return moved(written, next);
            }
            if (view.status() == CheckoutStatus.INCOMPLETE) {
                // out_of_stock at complete has no row of its own in table 7.2; it is "checkout incomplete" and the
                // re-quote that follows ends FAILED_NOT_CHARGED via the QUOTING out_of_stock row.
                TransitionEvent event = codes.contains(PRICE_CHANGED)
                        ? TransitionEvent.PRICE_CHANGED
                        : TransitionEvent.CHECKOUT_INCOMPLETE;
                Move m = requote(written, event);
                settleSiblings(written, attempt, "incomplete");
                resolve(attempt, AttemptStatus.RESPONDED, http, summary, m.next(), written.version());
                requoteEvents(written, m);
                return moved(written, m.next());
            }
            if (view.status() == CheckoutStatus.READY_FOR_COMPLETE
                    && (codes.contains(PAYMENT_DECLINED) || codes.contains(PAYMENT_INVALID))) {
                Move m = move(written, TransitionEvent.PAYMENT_DECLINED);
                PurchaseIntent next = m.next().withFailureReason(PAYMENT_DECLINED);
                settleSiblings(written, attempt, "payment_declined");
                resolve(attempt, AttemptStatus.RESPONDED, http, summary, next, written.version());
                transitionEvent(written.id(), m.transition());
                return moved(written, next);
            }
            // 7.2: the merchant answered, did not complete, is not charging, and gave an error: not charged. A
            // complete_in_progress answer is excluded (a charge may be running), and so is a bare Ok with no error.
            Optional<MerchantMessage> error = view.messages().stream()
                    .filter(m -> m.type().equals("error")).findFirst();
            if (error.isPresent() && view.status() != CheckoutStatus.COMPLETED
                    && view.status() != CheckoutStatus.COMPLETE_IN_PROGRESS) {
                String code = error.get().code();
                Move m = move(written, TransitionEvent.PAYMENT_DECLINED);
                PurchaseIntent next = m.next().withFailureReason(
                        CANCEL_OWED.contains(code) ? code : COMPLETE_ERROR_PREFIX + code);
                settleSiblings(written, attempt, "not_charged");
                resolve(attempt, AttemptStatus.RESPONDED, http, summary, next, written.version());
                transitionEvent(written.id(), m.transition());
                return moved(written, next);
            }
            // Any other answer does not establish the outcome (8.1: when in doubt, Uncertain).
            return toReconciling(written, attempt, http, summary);
        }
        Optional<Integer> http = outcome instanceof CallOutcome.ProtocolError<CheckoutView> e
                ? Optional.of(e.httpStatus())
                : Optional.empty();
        // 8.2: complete is never retried on Uncertain. A ProtocolError (7.2 protocol_error) and any other answer that
        // is not Ok also go to RECONCILING: none of them proves that no order was created.
        TransitionEvent event = outcome instanceof CallOutcome.ProtocolError<CheckoutView>
                ? TransitionEvent.PROTOCOL_ERROR
                : TransitionEvent.UNCERTAIN;
        return toReconciling(written, attempt, http, Optional.of(describe(outcome)), event);
    }

    private Step toReconciling(
            PurchaseIntent written, MerchantAttempt attempt, Optional<Integer> http, Optional<String> response) {
        return toReconciling(written, attempt, http, response, TransitionEvent.UNCERTAIN);
    }

    private Step toReconciling(
            PurchaseIntent written, MerchantAttempt attempt, Optional<Integer> http, Optional<String> response,
            TransitionEvent event) {
        Move m = move(written, event);
        PurchaseIntent next = withUncertainSince(m.next());
        resolve(attempt, AttemptStatus.UNCERTAIN, http, response, next, written.version());
        transitionEvent(written.id(), m.transition());
        return moved(written, next);
    }

    // ---------------------------------------------------------------- COMPLETING without a live call

    private Step completing(PurchaseIntent intent, List<MerchantAttempt> attempts) {
        List<MerchantAttempt> sameKey = completesUnder(attempts, intent.requoteCount());
        if (sameKey.isEmpty()) {
            throw new IllegalStateException("COMPLETING without a complete attempt: " + intent.id());
        }
        MerchantAttempt latest = sameKey.get(sameKey.size() - 1);
        return switch (latest.status()) {
            case SENT -> {
                // 7.3 crash point: the request may or may not have been processed. Reconcile; never resend blind.
                Move m = move(intent, TransitionEvent.UNCERTAIN);
                PurchaseIntent next = withUncertainSince(m.next());
                resolve(latest, AttemptStatus.UNCERTAIN, Optional.empty(),
                        Optional.of(CanonicalJson.write(Map.of("resolved_by", "recovery"))), next, intent.version());
                transitionEvent(intent.id(), m.transition());
                yield moved(intent, next);
            }
            case UNCERTAIN -> {
                // Reconciliation approved a resend and left this marker (see reconcile). Only the process that read
                // ready_for_complete continues to it; after a restart or takeover nothing is sent and the next step
                // reads again (7.6).
                Long approvedAt = approvedResends.remove(intent.id());
                if (approvedAt != null && approvedAt == intent.version()) {
                    yield resend(intent, sameKey);
                }
                // A takeover never moves the deadline: the persisted uncertain_since is kept (7.5 rule 1).
                Move m = move(intent, TransitionEvent.UNCERTAIN);
                PurchaseIntent next = withUncertainSince(m.next());
                intents.update(next, intent.version(), workerId);
                event(intent.id(), "resend_not_approved_here", Map.of("attempt_id", latest.id().value()));
                transitionEvent(intent.id(), m.transition());
                yield moved(intent, next);
            }
            case RESPONDED -> throw new IllegalStateException(
                    "COMPLETING with a resolved complete attempt: " + intent.id());
        };
    }

    /** The resend approved by reconciliation: the stored key and byte-identical stored body (I7). */
    private Step resend(PurchaseIntent intent, List<MerchantAttempt> sameKey) {
        MerchantGateway merchant = gateway(intent);
        MerchantAttempt first = sameKey.get(0);
        FrozenBody body = first.requestBody()
                .orElseThrow(() -> new IllegalStateException("I7: complete attempt without a stored body"));
        IdempotencyKey key = first.idempotencyKey()
                .orElseThrow(() -> new IllegalStateException("I7: complete attempt without a key"));
        MerchantAttempt attempt = newAttempt(intent.id(), AttemptOp.COMPLETE, first.revision(), key, Optional.of(body));
        PurchaseIntent written = writeAhead(intent, attempt, intent.withState(COMPLETING, clock.instant()));
        event(intent.id(), "complete_resent", Map.of("attempt_id", attempt.id().value(), "resend", sameKey.size()));
        return onCompleteOutcome(written, attempt, merchant.complete(requireSession(intent), body, key));
    }

    // ---------------------------------------------------------------- RECONCILING (7.5)

    private Step reconcile(PurchaseIntent intent, List<MerchantAttempt> attempts) {
        MerchantGateway merchant = gateway(intent);
        SessionId session = requireSession(intent);
        Instant since = intent.uncertainSince()
                .orElseThrow(() -> new IllegalStateException("RECONCILING without uncertain_since: " + intent.id()));
        List<MerchantAttempt> sameKey = completesUnder(attempts, intent.requoteCount());
        Instant now = clock.instant();
        boolean pastDeadline = !now.isBefore(since.plus(config.reconcileDeadline()));
        Instant lastSent = sameKey.stream().map(MerchantAttempt::sentAt).max(Comparator.naturalOrder()).orElse(since);
        boolean quietElapsed = !now.isBefore(lastSent.plus(config.resendQuietPeriod()));

        CallOutcome<CheckoutView> read = merchant.get(session);
        event(intent.id(), "reconcile_read", readSummary(read));
        if (!(read instanceof CallOutcome.Ok<CheckoutView> ok)) {
            return pastDeadline ? escalate(intent, attempts) : waiting(intent, "read_failed");
        }
        CheckoutView view = ok.value();
        return switch (view.status()) {
            case COMPLETED -> {
                if (view.order().isEmpty()) {
                    yield pastDeadline ? escalate(intent, attempts) : waiting(intent, "completed_without_order");
                }
                settle(intent, sameKey, "reconciliation", "completed");
                Move m = move(intent, TransitionEvent.OBSERVED_COMPLETED);
                PurchaseIntent next = m.next().withOrderId(view.order().get().id());
                intents.update(next, intent.version(), workerId);
                transitionEvent(intent.id(), m.transition());
                yield moved(intent, next);
            }
            case CANCELED -> {
                settle(intent, sameKey, "reconciliation", "canceled");
                Move m = move(intent, TransitionEvent.OBSERVED_CANCELED);
                PurchaseIntent next = m.next().withFailureReason(CANCELED_BY_MERCHANT);
                intents.update(next, intent.version(), workerId);
                transitionEvent(intent.id(), m.transition());
                yield moved(intent, next);
            }
            case COMPLETE_IN_PROGRESS -> pastDeadline
                    ? escalate(intent, attempts)
                    : pollAgain(intent, TransitionEvent.OBSERVED_COMPLETE_IN_PROGRESS);
            case READY_FOR_COMPLETE -> {
                if (pastDeadline) {
                    yield escalate(intent, attempts);
                }
                if (!quietElapsed) {
                    yield pollAgain(intent, TransitionEvent.OBSERVED_READY_FOR_COMPLETE,
                            TransitionGuard.QUIET_PERIOD_NOT_ELAPSED);
                }
                if (sameKey.size() - 1 >= config.maxCompleteResends()) {
                    // DESIGN GAP: table 7.2 has no row for ready_for_complete with no resends left. Keep polling;
                    // the deadline row ends it in ESCALATED.
                    yield waiting(intent, "resends_exhausted");
                }
                // The resend itself is the next step's one call (see completing()). Its marker is the newest
                // attempt being UNCERTAIN, so a leftover SENT one is resolved in the same write.
                Move m = move(intent, TransitionEvent.OBSERVED_READY_FOR_COMPLETE, TransitionGuard.RESEND_ALLOWED);
                MerchantAttempt latest = sameKey.get(sameKey.size() - 1);
                if (latest.status() == AttemptStatus.SENT) {
                    resolve(latest, AttemptStatus.UNCERTAIN, Optional.empty(),
                            Optional.of(CanonicalJson.write(Map.of("resolved_by", "reconciliation"))), m.next(),
                            intent.version());
                } else {
                    intents.update(m.next(), intent.version(), workerId);
                }
                // The version the write actually stored, read back: both branches above are repository writes, and
                // the port does not promise expected + 1.
                approvedResends.put(intent.id(), intents.findForSystem(intent.id())
                        .orElseThrow(() -> new IntentNotFoundException("no such intent: " + intent.id())).version());
                transitionEvent(intent.id(), m.transition());
                yield moved(intent, m.next());
            }
            case INCOMPLETE -> {
                boolean unresolved = sameKey.stream().anyMatch(a -> a.status() != AttemptStatus.RESPONDED);
                if (unresolved && !quietElapsed) {
                    // Not in table 7.2: a complete still in flight could land after the re-quote's PUT re-prices the
                    // session and charge an unapproved total. Wait out the quiet period first, as for a resend.
                    yield pastDeadline ? escalate(intent, attempts) : waiting(intent, "incomplete_within_quiet_period");
                }
                settle(intent, sameKey, "reconciliation", "incomplete");
                Move m = requote(intent, TransitionEvent.OBSERVED_INCOMPLETE);
                intents.update(m.next(), intent.version(), workerId);
                requoteEvents(intent, m);
                yield moved(intent, m.next());
            }
        };
    }

    private Step escalate(PurchaseIntent intent, List<MerchantAttempt> attempts) {
        Move m = move(intent, TransitionEvent.DEADLINE_REACHED);
        PurchaseIntent next = m.next().withFailureReason(OUTCOME_UNKNOWN);
        intents.update(next, intent.version(), workerId);
        event(intent.id(), "escalated", Map.of("attempts", attempts.stream().map(a -> Map.of(
                "attempt_id", a.id().value(), "op", a.op().name(), "revision", a.revision(),
                "status", a.status().name(), "sent_at", a.sentAt().toString())).toList()));
        transitionEvent(intent.id(), m.transition());
        return moved(intent, next);
    }

    /**
     * I3: the newest approval is missing, decided against, expired, or for another snapshot or another session than the
     * intent was approved with. Nothing is sent and no attempt is written; the intent goes to ESCALATED with a reason, so a human
     * looks at it instead of the worker retrying forever.
     */
    private Step escalateInvalidApproval(
            PurchaseIntent intent, List<MerchantAttempt> attempts, Optional<ApprovalRecord> latest,
            Optional<String> approvedHash) {
        Move m = move(intent, TransitionEvent.APPROVAL_INVALID);
        PurchaseIntent next = m.next().withFailureReason(APPROVAL_INVALID);
        intents.update(next, intent.version(), workerId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approval_status", latest.map(a -> a.status().name()).orElse("NONE"));
        payload.put("hash_matches", latest.isPresent() && approvedHash.equals(latest.map(ApprovalRecord::snapshotHash)));
        event(intent.id(), "approval_invalid", payload);
        event(intent.id(), "escalated", Map.of("attempts", attempts.stream().map(a -> Map.of(
                "attempt_id", a.id().value(), "op", a.op().name(), "revision", a.revision(),
                "status", a.status().name(), "sent_at", a.sentAt().toString())).toList()));
        transitionEvent(intent.id(), m.transition());
        return moved(intent, next);
    }

    /** A RECONCILING -> RECONCILING row: nothing to write but the event. */
    private Step pollAgain(PurchaseIntent intent, TransitionEvent event, TransitionGuard... guards) {
        Move m = move(intent, event, guards);
        transitionEvent(intent.id(), m.transition());
        return new Step(intent.state(), intent.state(), false, Optional.empty());
    }

    /** Keep polling in a situation table 7.2 has no row for; the deadline still applies. */
    private Step waiting(PurchaseIntent intent, String reason) {
        event(intent.id(), "reconcile_wait", Map.of("reason", reason));
        return new Step(intent.state(), intent.state(), false, Optional.empty());
    }

    /**
     * Records what was established for attempts whose own response was lost (the 7.4 guard reads it). A fenced write
     * (7.6): a worker whose lease was taken, or whose copy is stale, fails here and settles nothing. The intent is
     * written by the caller's own fenced write that follows, at the same {@code expectedVersion}.
     */
    private void settle(
            PurchaseIntent intent, List<MerchantAttempt> sameKey, String resolvedBy, String observed) {
        List<AttemptId> unresolved = sameKey.stream().filter(a -> a.status() != AttemptStatus.RESPONDED)
                .map(MerchantAttempt::id).toList();
        if (unresolved.isEmpty()) {
            return;
        }
        intents.resolveAttempts(intent.id(), unresolved, AttemptStatus.RESPONDED, Optional.empty(),
                Optional.of(CanonicalJson.write(Map.of("resolved_by", resolvedBy, "observed", observed))),
                intent.version(), workerId);
    }

    /**
     * A definitive answer to one {@code complete} attempt is the merchant's stored result for its key (A.1), so it
     * also settles the earlier attempts under that key; a late arrival of any of them replays the same result.
     * Written before the fenced outcome write, so a crash in between cannot leave them unresolved (7.4 guard).
     */
    private void settleSiblings(PurchaseIntent written, MerchantAttempt answered, String observed) {
        settle(written, completesUnder(intents.attempts(answered.intentId()), answered.revision()).stream()
                .filter(a -> !a.id().equals(answered.id())).toList(), "same_key_answer", observed);
    }

    // ---------------------------------------------------------------- re-quote (P7, P8)

    /** Table 7.2 re-quote rows: below the P8 cap back to QUOTING with requote_count + 1, at the cap REJECTED. */
    private Move requote(PurchaseIntent intent, TransitionEvent event) {
        Move m = move(intent, event, requotePolicy.requoteGuard(intent.requoteCount()));
        PurchaseIntent next = m.transition().to() == QUOTING
                ? m.next().withRequoteCount(intent.requoteCount() + 1).withApprovedSnapshotHash(Optional.empty())
                : m.next().withFailureReason(RequotePolicy.PRICE_UNSTABLE);
        return new Move(next, m.transition());
    }

    private void requoteEvents(PurchaseIntent before, Move m) {
        policyEvent(before.id(), "requote", requotePolicy.evaluateRequoteCap(before.requoteCount()));
        transitionEvent(before.id(), m.transition());
    }

    // ---------------------------------------------------------------- owed cancel

    /**
     * The owed cancel (7.6): sent under the key cancel/0 while {@code cancel_pending} is set. A definitive answer clears
     * the flag; so does the third unanswered attempt, which gives up. The intent's state never changes. An attempt a
     * crash left SENT counts as one of the three.
     */
    private Step cancelIfOwed(PurchaseIntent intent, List<MerchantAttempt> attempts) {
        if (!intent.cancelPending()) {
            return idle(intent);
        }
        if (intent.sessionId().isEmpty()) {
            return probeOrphanSession(intent, attempts);
        }
        long tried = attempts.stream().filter(a -> a.op() == AttemptOp.CANCEL).count();
        if (tried >= MAX_CANCEL_ATTEMPTS) {
            PurchaseIntent next = intent.withCancelPending(false).withState(intent.state(), clock.instant());
            intents.update(next, intent.version(), workerId);
            event(intent.id(), "cancel_gave_up", Map.of("attempts", tried));
            return moved(intent, next);
        }
        MerchantGateway merchant = gateway(intent);
        IdempotencyKey key = IdempotencyKey.derive(intent.id(), MutatingOp.CANCEL, 0);
        MerchantAttempt attempt = newAttempt(intent.id(), AttemptOp.CANCEL, 0, key, Optional.empty());
        PurchaseIntent written = writeAhead(intent, attempt, intent.withState(intent.state(), clock.instant()));
        CallOutcome<CheckoutView> outcome = merchant.cancel(requireSession(intent), key);
        boolean definitive = switch (outcome) {
            case CallOutcome.Ok<CheckoutView> ok -> true;
            case CallOutcome.Rejected<CheckoutView> r -> true;
            case CallOutcome.ProtocolError<CheckoutView> e -> !isRetryable(e);
            case CallOutcome.NotSent<CheckoutView> n -> false;
            case CallOutcome.Uncertain<CheckoutView> u -> false;
        };
        boolean givingUp = !definitive && tried + 1 >= MAX_CANCEL_ATTEMPTS;
        Optional<Integer> http = switch (outcome) {
            case CallOutcome.Ok<CheckoutView> ok -> Optional.of(ok.httpStatus());
            case CallOutcome.ProtocolError<CheckoutView> e -> Optional.of(e.httpStatus());
            default -> Optional.empty();
        };
        Optional<String> response = outcome instanceof CallOutcome.Ok<CheckoutView> ok
                ? Optional.of(CheckoutWire.responseSummary(ok.value()))
                : Optional.of(describe(outcome));
        PurchaseIntent next = written.withCancelPending(!(definitive || givingUp))
                .withState(written.state(), clock.instant());
        resolve(attempt, definitive ? AttemptStatus.RESPONDED : AttemptStatus.UNCERTAIN, http, response, next,
                written.version());
        event(intent.id(), "cancel_sent", Map.of("outcome", kind(outcome),
                "attempt", tried + 1));
        if (givingUp) {
            event(intent.id(), "cancel_gave_up", Map.of("attempts", tried + 1));
        }
        return new Step(intent.state(), intent.state(), false, Optional.empty());
    }

    /**
     * The orphan-session probe (7.6 M3 note). A create that may have made a session the intent never learned of left
     * {@code cancel_pending} set with no session id. One step re-sends that create under its own key, only when the body
     * rebuilt from the intent is byte-identical to the frozen one (else nothing is sent), to learn the session id from the
     * merchant's stored result; the next step cancels it. It never completes: the intent is terminal. At most
     * {@link #MAX_PROBE_ATTEMPTS} probes; a Rejected answer ends the probe (no session exists), any other answer that is not Ok
     * counts, and the last one gives up with an event.
     */
    private Step probeOrphanSession(PurchaseIntent intent, List<MerchantAttempt> attempts) {
        List<MerchantAttempt> creates = attempts.stream()
                .filter(a -> a.op() == AttemptOp.CREATE && a.revision() == 0).toList();
        if (creates.isEmpty()) {
            return stopProbing(intent, "orphan_probe_no_create", Map.of());
        }
        int probes = creates.size() - 1;
        if (probes >= MAX_PROBE_ATTEMPTS) {
            return stopProbing(intent, "orphan_probe_gave_up", Map.of("probes", probes));
        }
        MerchantAttempt original = creates.get(0);
        CreateCheckout request = new CreateCheckout(intent.lines(), Optional.of(intent.buyerEmail()));
        FrozenBody frozen = original.requestBody().orElse(null);
        IdempotencyKey key = original.idempotencyKey().orElse(null);
        if (frozen == null || key == null || !frozen.json().equals(CheckoutWire.createBody(request))) {
            return stopProbing(intent, "orphan_probe_body_mismatch", Map.of("attempt_id", original.id().value()));
        }

        MerchantGateway merchant = gateway(intent);
        // An attempt left SENT by a crash is settled as uncertain first, fenced like every write here; it counts as a probe.
        List<AttemptId> unresolved = creates.stream().filter(a -> a.status() == AttemptStatus.SENT)
                .map(MerchantAttempt::id).toList();
        if (!unresolved.isEmpty()) {
            intents.resolveAttempts(intent.id(), unresolved, AttemptStatus.UNCERTAIN, Optional.empty(),
                    Optional.of(CanonicalJson.write(Map.of("resolved_by", "recovery"))), intent.version(), workerId);
        }
        MerchantAttempt attempt = newAttempt(intent.id(), AttemptOp.CREATE, 0, key, Optional.of(frozen));
        PurchaseIntent written = writeAhead(intent, attempt, intent.withState(intent.state(), clock.instant()));
        CallOutcome<CheckoutView> outcome = merchant.create(request, key);

        if (outcome instanceof CallOutcome.Ok<CheckoutView> ok) {
            PurchaseIntent next = written.withSessionId(ok.value().id()).withState(written.state(), clock.instant());
            resolve(attempt, AttemptStatus.RESPONDED, Optional.of(ok.httpStatus()),
                    Optional.of(CheckoutWire.responseSummary(ok.value())), next, written.version());
            event(intent.id(), "orphan_session_found", Map.of("probe", probes + 1));
            return new Step(intent.state(), intent.state(), false, Optional.empty());
        }
        if (outcome instanceof CallOutcome.Rejected<CheckoutView> r) {
            // The merchant answered the create with no session: it holds none for this key, so there is nothing to cancel.
            PurchaseIntent next = written.withCancelPending(false).withState(written.state(), clock.instant());
            resolve(attempt, AttemptStatus.RESPONDED, Optional.of(r.httpStatus()), Optional.of(describe(r)), next,
                    written.version());
            event(intent.id(), "orphan_probe_no_session", Map.of("probe", probes + 1));
            return new Step(intent.state(), intent.state(), false, Optional.empty());
        }
        boolean answered = outcome instanceof CallOutcome.ProtocolError<CheckoutView>;
        Optional<Integer> http = outcome instanceof CallOutcome.ProtocolError<CheckoutView> e
                ? Optional.of(e.httpStatus())
                : Optional.empty();
        boolean last = probes + 1 >= MAX_PROBE_ATTEMPTS;
        PurchaseIntent next = written.withCancelPending(!last).withState(written.state(), clock.instant());
        resolve(attempt, answered ? AttemptStatus.RESPONDED : AttemptStatus.UNCERTAIN, http,
                Optional.of(describe(outcome)), next, written.version());
        event(intent.id(), "orphan_probe_sent", Map.of("outcome", kind(outcome), "probe", probes + 1));
        if (last) {
            event(intent.id(), "orphan_probe_gave_up", Map.of("probes", probes + 1));
        }
        return new Step(intent.state(), intent.state(), false, Optional.empty());
    }

    /** Clears the owed cancel without sending anything, and leaves an event for the operator. */
    private Step stopProbing(PurchaseIntent intent, String eventType, Map<String, ?> payload) {
        PurchaseIntent next = intent.withCancelPending(false).withState(intent.state(), clock.instant());
        intents.update(next, intent.version(), workerId);
        event(intent.id(), eventType, payload);
        return moved(intent, next);
    }

    // ---------------------------------------------------------------- helpers

    private Optional<ProposalResult> existingProposal(UserId owner, String dedupeKey) {
        return intents.findActiveByDedupeKey(dedupeKey)
                .filter(i -> i.owner().equals(owner))
                .map(i -> new ProposalResult(i.id(), i.state(), Optional.empty()));
    }

    private Move move(PurchaseIntent intent, TransitionEvent event, TransitionGuard... guards) {
        Transition t = TransitionTable.resolve(intent.state(), event, Set.of(guards));
        return new Move(intent.moved(t, clock.instant()), t);
    }

    private MerchantAttempt newAttempt(
            IntentId intentId, AttemptOp op, int revision, IdempotencyKey key, Optional<FrozenBody> body) {
        return new MerchantAttempt(new AttemptId(ids.next()), intentId, op, revision, Optional.of(key),
                body.map(b -> Sha256.hex(b.json())), body, AttemptStatus.SENT, Optional.empty(), Optional.empty(),
                clock.instant(), Optional.empty());
    }

    /** 7.3 step 3: the attempt row and the intent in one fenced write. Returns the intent as stored (v + 1). */
    private PurchaseIntent writeAhead(PurchaseIntent current, MerchantAttempt attempt, PurchaseIntent next) {
        intents.recordAttempt(attempt, next, current.version(), workerId);
        return next.withVersion(current.version() + 1);
    }

    /** 7.3 step 5: the attempt outcome and the state change in one fenced write. */
    private void resolve(
            MerchantAttempt attempt, AttemptStatus status, Optional<Integer> http, Optional<String> response,
            PurchaseIntent next, long expectedVersion) {
        intents.resolveAttempt(attempt.id(), status, http, response, next, expectedVersion, workerId);
    }

    /**
     * Sets uncertain_since only once per complete key, so a restart or a later uncertainty under the same key never
     * resets the deadline; {@link #sendComplete} clears it when it issues a new key (7.5 rule 1).
     */
    private PurchaseIntent withUncertainSince(PurchaseIntent intent) {
        return intent.uncertainSince().isPresent() ? intent : intent.withUncertainSince(clock.instant());
    }

    private static List<MerchantAttempt> completes(List<MerchantAttempt> attempts) {
        return attempts.stream().filter(a -> a.op() == AttemptOp.COMPLETE).toList();
    }

    private static List<MerchantAttempt> completesUnder(List<MerchantAttempt> attempts, int revision) {
        return attempts.stream().filter(a -> a.op() == AttemptOp.COMPLETE && a.revision() == revision).toList();
    }

    private static SessionId requireSession(PurchaseIntent intent) {
        return intent.sessionId().orElseThrow(() -> new IllegalStateException("no session: " + intent.id()));
    }

    private static Set<String> codes(CheckoutView view) {
        return view.messages().stream().map(MerchantMessage::code).collect(Collectors.toSet());
    }

    /** Whether the quote has exactly the items and quantities of the proposal (summed per item, order ignored). */
    private static boolean sameBasket(PurchaseIntent intent, CheckoutView view) {
        Map<String, Integer> proposed = new java.util.HashMap<>();
        intent.lines().forEach(l -> proposed.merge(l.itemId(), l.quantity(), Integer::sum));
        Map<String, Integer> quoted = new java.util.HashMap<>();
        view.lines().forEach(l -> quoted.merge(l.itemId(), l.quantity(), Integer::sum));
        return proposed.equals(quoted);
    }

    /** The lines and buyer of the newest stored response that has them (8.4, decision L). */
    private static Optional<QuoteShape> lastQuoteShape(List<MerchantAttempt> attempts) {
        for (int i = attempts.size() - 1; i >= 0; i--) {
            Optional<QuoteShape> shape = attempts.get(i).responseJson().flatMap(CheckoutWire::quoteShape);
            if (shape.isPresent()) {
                return shape;
            }
        }
        return Optional.empty();
    }

    private static String rejectedReason(CallOutcome.Rejected<CheckoutView> r) {
        Set<String> codes = r.messages().stream().map(MerchantMessage::code).collect(Collectors.toSet());
        return codes.contains(OUT_OF_STOCK) ? OUT_OF_STOCK
                : codes.contains(ITEM_UNAVAILABLE) ? ITEM_UNAVAILABLE : CREATE_REJECTED_REASON;
    }

    private static boolean isRetryable(CallOutcome.ProtocolError<CheckoutView> e) {
        return e.retryAfter().isPresent() || e.httpStatus() == 429 || e.httpStatus() == 503;
    }

    private static String policyReason(PolicyDecision decision) {
        return POLICY_PREFIX + decision.ruleId().map(Enum::name).orElse("unknown");
    }

    private static String kind(CallOutcome<?> outcome) {
        return switch (outcome) {
            case CallOutcome.Ok<?> ok -> "ok";
            case CallOutcome.NotSent<?> n -> "not_sent";
            case CallOutcome.Uncertain<?> u -> "uncertain";
            case CallOutcome.ProtocolError<?> e -> "protocol_error";
            case CallOutcome.Rejected<?> r -> "rejected";
        };
    }

    private static String describe(CallOutcome<?> outcome) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", kind(outcome));
        switch (outcome) {
            case CallOutcome.NotSent<?> n -> m.put("cause", n.cause());
            case CallOutcome.Uncertain<?> u -> m.put("cause", u.cause());
            case CallOutcome.ProtocolError<?> e -> {
                m.put("http_status", e.httpStatus());
                m.put("code", e.code());
            }
            case CallOutcome.Ok<?> ok -> m.put("http_status", ok.httpStatus());
            case CallOutcome.Rejected<?> r -> {
                m.put("http_status", r.httpStatus());
                m.put("codes", r.messages().stream().map(MerchantMessage::code).toList());
            }
        }
        return CanonicalJson.write(m);
    }

    private static Map<String, Object> readSummary(CallOutcome<CheckoutView> read) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", kind(read));
        if (read instanceof CallOutcome.Ok<CheckoutView> ok) {
            m.put("status", ok.value().status().wire());
        }
        return m;
    }

    private Step idle(PurchaseIntent intent) {
        return new Step(intent.state(), intent.state(), true, Optional.empty());
    }

    private static Step moved(PurchaseIntent before, PurchaseIntent after) {
        return new Step(before.state(), after.state(), false, Optional.empty());
    }

    private void event(IntentId id, String type, Map<String, ?> payload) {
        intents.appendEvent(id, IntentEvent.of(type, payload, clock.instant()));
    }

    private void transitionEvent(IntentId id, Transition t) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", t.from().name());
        payload.put("event", t.event().name());
        payload.put("guard", t.guard().name());
        payload.put("to", t.to().name());
        event(id, "transition", payload);
    }

    private void policyEvent(IntentId id, String stage, PolicyDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        payload.put("allowed", decision.allowed());
        decision.ruleId().ifPresent(r -> payload.put("rule", r.name()));
        payload.put("reason", decision.reason());
        event(id, "policy_decision", payload);
    }
}
