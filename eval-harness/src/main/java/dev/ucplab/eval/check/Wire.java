package dev.ucplab.eval.check;

import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.eval.AutoApprover;
import dev.ucplab.eval.RunEvidence;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.faultproxy.PathPattern;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reading L and attributing it without trusting the agent (m4-plan section 5, after {@code Invariants}): an idempotency
 * key belongs to the intent it is derived from (7.4), computed here for every op and revision up to
 * {@link #MAX_REVISION}. Every M4 run holds one basket, so every order and every session in it belongs to the run.
 */
final class Wire {

    /** P8 allows 2 re-quotes, so a correct run uses complete revisions 0 to 2; the margin catches a runaway counter. */
    static final int MAX_REVISION = 8;

    static final String SESSIONS = "/ucp/v1/checkout-sessions";
    private static final PathPattern CREATE = new PathPattern(SESSIONS);
    private static final PathPattern COMPLETE = new PathPattern(SESSIONS + "/*/complete");

    private Wire() {
    }

    /** The op, intent and revision a key was derived for. */
    record KeyOwner(IntentId intent, MutatingOp op, int revision) {
    }

    /** The intents of the run: those {@code propose} returned and those D holds (attribution only). */
    static Set<IntentId> intents(RunEvidence e) {
        Set<IntentId> ids = new LinkedHashSet<>(e.proposed());
        e.intents().forEach(p -> ids.add(p.intent().id()));
        return ids;
    }

    static Map<String, KeyOwner> keyOwners(RunEvidence e) {
        Map<String, KeyOwner> owners = new HashMap<>();
        for (IntentId id : intents(e)) {
            for (MutatingOp op : MutatingOp.values()) {
                // 7.4: create and cancel always use revision 0.
                int maxRevision = op == MutatingOp.CREATE || op == MutatingOp.CANCEL ? 0 : MAX_REVISION;
                for (int revision = 0; revision <= maxRevision; revision++) {
                    owners.put(IdempotencyKey.derive(id, op, revision).value(), new KeyOwner(id, op, revision));
                }
            }
        }
        return owners;
    }

    static Optional<KeyOwner> owner(LogEntry l, Map<String, KeyOwner> owners) {
        return l.idempotencyKey().map(owners::get);
    }

    static boolean isCreate(LogEntry l) {
        return l.method().equals("POST") && CREATE.matches(pathOnly(l.path()));
    }

    static boolean isComplete(LogEntry l) {
        return l.method().equals("POST") && COMPLETE.matches(pathOnly(l.path()));
    }

    /** Every {@code complete} that reached the proxy, forwarded or not: the agent sent each one. */
    static List<LogEntry> completes(RunEvidence e) {
        return e.proxyLog().stream().filter(Wire::isComplete).toList();
    }

    /** The session a request path names ({@code /ucp/v1/checkout-sessions/{id}[/...]}), if any. */
    static Optional<String> session(String path) {
        String p = pathOnly(path);
        if (!p.startsWith(SESSIONS + "/")) {
            return Optional.empty();
        }
        String rest = p.substring(SESSIONS.length() + 1);
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        return id.isEmpty() ? Optional.empty() : Optional.of(id);
    }

    private static String pathOnly(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    /** The approver's decisions on {@code id}, in order. */
    static List<AutoApprover.Decision> decisions(RunEvidence e, IntentId id) {
        return e.approvals().stream().filter(d -> d.intentId().equals(id)).toList();
    }

    /** How a request appears in a violation: its seq, method, path and arrival. Never a body. */
    static String describe(LogEntry l) {
        return l.method() + " " + pathOnly(l.path()) + " #" + l.seq() + " at " + l.at();
    }

    static Violation aborted(String invariant, RunEvidence e) {
        return new Violation(invariant, "the run was aborted (" + e.aborted().orElseThrow() + "), so " + invariant
                + " could not be established");
    }
}
