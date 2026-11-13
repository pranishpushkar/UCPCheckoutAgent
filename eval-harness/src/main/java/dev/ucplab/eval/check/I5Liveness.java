package dev.ucplab.eval.check;

import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.eval.RunEvidence;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.ucp.UcpConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * I5: every intent reaches a terminal state ({@code ESCALATED} included) within its 7.6 deadline. I5 is about the
 * agent's own state, so its end state and the stretches it spent in each state are read from D (the final intent row
 * and its {@code transition} events, on the agent's clock); the harness's own observation and L anchor the last
 * stretch. Bounds, scaled with the run's configuration, each extended by one lease plus one worker period per crash:
 * <ul>
 *   <li>PROPOSED, QUOTING: one worker period plus one call of 4 attempts and its backoff (8.2);
 *   <li>APPROVED: one worker period plus the complete timeout;
 *   <li>AWAITING_APPROVAL: the approval's lifetime (not scaled);
 *   <li>COMPLETING and RECONCILING together, per complete key: the complete timeout (the deadline counts from
 *       {@code uncertain_since}, which is set when that call ends), the reconcile deadline, and one read in flight
 *       when the deadline passes (7.5 caps the poll at the deadline, but a read already sent finishes first).
 * </ul>
 * And from the wire: from the first {@code complete} in L under an intent's last complete key to the first time the
 * harness saw it terminal (with no cancel owed), at most the COMPLETING/RECONCILING bound plus one worker period and
 * {@value #CANCEL_ATTEMPTS} cancel calls (7.6, for an end that owes a cancel). Harness observations are late by at
 * most two of its polls.
 */
public final class I5Liveness implements InvariantCheck {

    /** 7.6: a best-effort cancel gives up after 3 attempts. */
    static final int CANCEL_ATTEMPTS = 3;

    @Override
    public String id() {
        return "I5";
    }

    @Override
    public String description() {
        return "every intent is terminal with no cancel owed, and no state outlasted its 7.6 bound";
    }

    @Override
    public String evidence() {
        return "D for the end state and the time in each state (I5 is about the agent's state), L and the harness's"
                + " own terminal observation for the last complete stretch";
    }

    @Override
    public List<Violation> check(RunEvidence e, CheckContext c) {
        if (e.aborted().isPresent()) {
            return List.of(Wire.aborted(id(), e));
        }
        List<Violation> v = new ArrayList<>();
        if (e.terminalSeenAt().isEmpty()) {
            v.add(new Violation(id(), "the harness never saw every intent terminal"));
        }
        for (IntentId id : e.proposed()) {
            if (e.intents().stream().noneMatch(p -> p.intent().id().equals(id))) {
                v.add(new Violation(id(), "propose returned " + id.value() + ", but D holds no such intent"));
            }
        }
        Duration crash = c.orchestrator().lease().plus(c.recovery().period()).multipliedBy(c.crashes());
        Duration slack = c.harnessPoll().multipliedBy(2);
        Duration completing = completingBound(c).plus(crash).plus(slack);
        for (RunEvidence.Persisted p : e.intents()) {
            IntentId id = p.intent().id();
            if (!p.intent().state().isTerminal()) {
                v.add(new Violation(id(), id.value() + " ended " + p.intent().state()));
            }
            if (p.intent().cancelPending()) {
                v.add(new Violation(id(), id.value() + " ended " + p.intent().state() + " still owing a cancel"));
            }
            stretches(p, c, crash.plus(slack), v);
            lastCompleteKey(e, id).ifPresent(first -> e.terminalSeenAt().ifPresent(seen -> {
                Duration bound = completing.plus(c.recovery().period())
                        .plus(oneCall(c.ucp(), c.ucp().requestTimeout()).multipliedBy(CANCEL_ATTEMPTS));
                Duration took = Duration.between(first.at(), seen);
                if (took.compareTo(bound) > 0) {
                    v.add(new Violation(id(), id.value() + " was first seen terminal " + took + " after "
                            + Wire.describe(first) + ", past its bound " + bound));
                }
            }));
        }
        return v;
    }

    /** One stretch of the 7.6 table: a state, or COMPLETING and RECONCILING together. */
    private static Optional<String> stretchOf(IntentState s) {
        return switch (s) {
            case PROPOSED, QUOTING, APPROVED, AWAITING_APPROVAL -> Optional.of(s.name());
            case COMPLETING, RECONCILING -> Optional.of("COMPLETING/RECONCILING");
            case COMPLETED, REJECTED, FAILED_NOT_CHARGED, ESCALATED -> Optional.empty();
        };
    }

    private static Duration bound(String stretch, CheckContext c) {
        Duration period = c.recovery().period();
        return switch (stretch) {
            case "PROPOSED", "QUOTING" -> period.plus(oneCall(c.ucp(), c.ucp().requestTimeout()));
            case "APPROVED" -> period.plus(c.orchestrator().completeRequestTimeout());
            case "AWAITING_APPROVAL" -> DefaultApprovalService.DEFAULT_TTL;
            default -> completingBound(c);
        };
    }

    private static Duration completingBound(CheckContext c) {
        return c.orchestrator().completeRequestTimeout().plus(c.orchestrator().reconcileDeadline())
                .plus(oneCall(c.ucp(), c.ucp().requestTimeout()));
    }

    /** The longest one gateway call can take: every attempt timing out, plus the capped backoff between them (8.2). */
    static Duration oneCall(UcpConfig ucp, Duration timeout) {
        Duration total = timeout.multipliedBy(ucp.maxRetries() + 1L);
        Duration delay = ucp.backoffInitial();
        for (int i = 0; i < ucp.maxRetries(); i++) {
            total = total.plus(delay.compareTo(ucp.backoffCap()) > 0 ? ucp.backoffCap() : delay);
            delay = delay.multipliedBy(2);
        }
        return total;
    }

    /** Walks D's transition events and reports any stretch that outlasted its bound. */
    private void stretches(RunEvidence.Persisted p, CheckContext c, Duration extra, List<Violation> v) {
        Optional<String> stretch = stretchOf(IntentState.PROPOSED);
        Instant start = p.intent().createdAt();
        for (IntentEvent ev : p.events()) {
            if (!ev.type().equals("transition")) {
                continue;
            }
            Map<String, Object> t = CanonicalJson.readObject(ev.payloadJson());
            Optional<String> next = stretchOf(IntentState.valueOf((String) t.get("to")));
            if (next.equals(stretch)) {
                continue;
            }
            if (stretch.isPresent()) {
                Duration took = Duration.between(start, ev.at());
                Duration limit = bound(stretch.get(), c).plus(extra);
                if (took.compareTo(limit) > 0) {
                    v.add(new Violation(id(), p.intent().id().value() + " spent " + took + " in "
                            + stretch.get() + " from " + start + ", past its bound " + limit));
                }
            }
            stretch = next;
            start = ev.at();
        }
    }

    /** The first complete in L under the highest complete revision the intent used. */
    private static Optional<LogEntry> lastCompleteKey(RunEvidence e, IntentId id) {
        Map<String, Wire.KeyOwner> owners = Wire.keyOwners(e);
        List<LogEntry> mine = Wire.completes(e).stream()
                .filter(l -> Wire.owner(l, owners)
                        .map(o -> o.intent().equals(id) && o.op() == MutatingOp.COMPLETE).orElse(false))
                .toList();
        int top = mine.stream().mapToInt(l -> owners.get(l.idempotencyKey().orElseThrow()).revision()).max()
                .orElse(-1);
        return mine.stream().filter(l -> owners.get(l.idempotencyKey().orElseThrow()).revision() == top).findFirst();
    }
}
