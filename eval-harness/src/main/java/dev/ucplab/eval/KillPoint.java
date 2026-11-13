package dev.ucplab.eval;

import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.faultproxy.Action;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.faultproxy.Match;
import dev.ucplab.faultproxy.PathPattern;
import dev.ucplab.faultproxy.Rule;
import dev.ucplab.faultproxy.RuleSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where S9 and S28 kill the agent child process with kill -9 (m4-plan section 8). Each point has the proxy rules that
 * hold the run there, and the condition, read from the proxy log and the database file, that says the moment has
 * come. The harness polls for it; it never sleeps for a guessed time.
 */
public enum KillPoint {

    /** COMPLETING, the complete held at the proxy before it is forwarded (a delay of 1.2 Tc); it lands after the kill. */
    K1,
    /** COMPLETING, the merchant has committed and its answer is held (1.5 Tc). */
    K2,
    /** RECONCILING: the complete's answer dropped, the first read held (2 pollMax) while the process dies. */
    K3,
    /**
     * S28, late in RECONCILING: every read answered 503, the kill comes once {@code D - L/2} has passed since
     * {@code uncertain_since} and a later read is on the wire, so the takeover comes after the deadline. The complete
     * is closed before forwarding in even runs (no order) and dropped after the merchant processed it in odd runs.
     */
    LATE;

    static final String SESSION = "/ucp/v1/checkout-sessions/*";

    /** S9 spreads its runs over K1 to K3 in turn (m4-plan section 8: 67/67/66 over 200). */
    public static KillPoint forS9(int run) {
        return values()[Math.floorMod(run, 3)];
    }

    /** The rules that hold a run at this point. */
    public RuleSet rules(TimeScale t, int run) {
        Duration tc = t.completeTimeout();
        Duration pollMax = t.orchestrator().pollMax();
        Match complete = Match.of("POST", Scenarios.COMPLETE).withNth(1);
        List<Rule> rules = switch (this) {
            case K1 -> List.of(new Rule("hold-before-forward", complete,
                    new Action.Delay(tc.multipliedBy(6).dividedBy(5).toMillis())).withTimes(1));
            case K2 -> List.of(new Rule("hold-answer", complete,
                    new Action.ForwardThenDelayResponse(tc.multipliedBy(3).dividedBy(2).toMillis())).withTimes(1));
            case K3 -> List.of(
                    new Rule("drop-complete", complete, new Action.ForwardThenDropResponse()).withTimes(1),
                    new Rule("hold-read", Match.of("GET", SESSION).withNth(1),
                            new Action.Delay(pollMax.multipliedBy(2).toMillis())).withTimes(1));
            case LATE -> List.of(
                    new Rule(run % 2 == 0 ? "close-complete" : "drop-complete", complete,
                            run % 2 == 0 ? Action.FailBeforeForward.closeConnection()
                                    : new Action.ForwardThenDropResponse()).withTimes(1),
                    new Rule("reads-503", Match.of("GET", SESSION), Action.FailBeforeForward.status(503)));
        };
        return new RuleSet(run, rules);
    }

    /**
     * Whether the kill is due, given the run's proxy log so far and the intent as the database shows it now.
     *
     * @param now the harness's clock, the same system clock the agent writes {@code uncertain_since} with
     */
    public boolean due(List<LogEntry> log, Optional<PurchaseIntent> intent, TimeScale t, Instant now) {
        PathPattern completePath = new PathPattern(Scenarios.COMPLETE);
        PathPattern sessionPath = new PathPattern(SESSION);
        List<LogEntry> completes = log.stream()
                .filter(l -> l.method().equals("POST") && completePath.matches(l.path())).toList();
        return switch (this) {
            case K1 -> !completes.isEmpty();
            case K2 -> completes.stream().anyMatch(l -> l.merchantStatus().isPresent());
            case K3 -> !completes.isEmpty() && log.stream().anyMatch(l -> l.method().equals("GET")
                    && sessionPath.matches(l.path()) && l.seq() > completes.getFirst().seq());
            case LATE -> intent.flatMap(PurchaseIntent::uncertainSince).map(since -> {
                Instant late = since.plus(t.orchestrator().reconcileDeadline()).minus(t.lease().dividedBy(2));
                return !now.isBefore(late) && log.stream().anyMatch(l -> l.method().equals("GET")
                        && sessionPath.matches(l.path()) && l.at().isAfter(late));
            }).orElse(false);
        };
    }
}
