package dev.ucplab.eval.check;

import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.eval.RunEvidence;
import dev.ucplab.faultproxy.LogEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * I4: after the first {@code complete} is sent, the intent's merchant session id never changes and no new session is
 * created for it. From the wire: after the intent's first complete in L, no create goes out under the intent's create
 * key, and every request that names a session names that one. A request without a key (a GET) is the intent's when
 * the run has a single intent, which every M4 row except S4's duplicate proposal has; S4's two proposals are one intent
 * too.
 *
 * <p>D is read for the one thing I4 is about besides the wire: the session id the agent finally stored must be the
 * session the first complete went to.
 */
public final class I4SessionFixed implements InvariantCheck {

    @Override
    public String id() {
        return "I4";
    }

    @Override
    public String description() {
        return "after the first complete, no create for the intent and no request to another session, and the stored"
                + " session id is the completed one";
    }

    @Override
    public String evidence() {
        return "L (every request after the intent's first complete); D only for the final stored session_id, which I4"
                + " is about";
    }

    @Override
    public List<Violation> check(RunEvidence e, CheckContext c) {
        if (e.aborted().isPresent()) {
            return List.of(Wire.aborted(id(), e));
        }
        List<Violation> v = new ArrayList<>();
        Map<String, Wire.KeyOwner> owners = Wire.keyOwners(e);
        Set<IntentId> intents = Wire.intents(e);
        for (IntentId id : intents) {
            Optional<LogEntry> first = Wire.completes(e).stream()
                    .filter(l -> Wire.owner(l, owners).map(o -> o.intent().equals(id)).orElse(false))
                    .findFirst();
            if (first.isEmpty()) {
                continue;
            }
            String session = Wire.session(first.get().path()).orElseThrow();
            String after = " after the first complete of " + id.value() + " (" + Wire.describe(first.get())
                    + ") went to " + session;
            for (LogEntry l : e.proxyLog()) {
                if (l.seq() <= first.get().seq()) {
                    continue;
                }
                Optional<Wire.KeyOwner> owner = Wire.owner(l, owners);
                boolean mine = owner.map(o -> o.intent().equals(id))
                        .orElse(l.idempotencyKey().isEmpty() && intents.size() == 1);
                if (Wire.isCreate(l)) {
                    if (owner.map(o -> o.intent().equals(id) && o.op() == MutatingOp.CREATE).orElse(false)) {
                        v.add(new Violation(id(), Wire.describe(l) + " is a create under the intent's key" + after));
                    } else if (mine) {
                        v.add(new Violation(id(), Wire.describe(l) + " is a create" + after));
                    }
                    continue;
                }
                Optional<String> target = Wire.session(l.path());
                if (mine && target.isPresent() && !target.get().equals(session)) {
                    v.add(new Violation(id(), Wire.describe(l) + " targets " + target.get() + after));
                }
            }
            e.intents().stream().filter(p -> p.intent().id().equals(id)).findFirst().ifPresent(p -> {
                Optional<String> stored = p.intent().sessionId().map(SessionId::value);
                if (!stored.equals(Optional.of(session))) {
                    v.add(new Violation(id(), id.value() + " finally stored session " + stored.orElse("none")
                            + after));
                }
            });
        }
        return v;
    }
}
