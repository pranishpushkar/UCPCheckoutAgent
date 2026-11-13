package dev.ucplab.eval.check;

import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.eval.AutoApprover;
import dev.ucplab.eval.GroundTruth;
import dev.ucplab.eval.RunEvidence;
import dev.ucplab.faultproxy.LogEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * I2: any order matches the approved snapshot: same merchant, items, quantities and currency, and a total not above
 * the approved maximum. {@code /_lab/orders} carries no lines or currency, so each order is joined to the merchant's
 * own GET of its session (13.1, M4 note).
 *
 * <p>The snapshot an order is held to is the last approval the approver logged before the first {@code complete}
 * under the order's key. The order's key is the highest complete revision forwarded on the order's session: the
 * merchant answers every complete 200, a {@code price_changed} one included, so a 2xx does not show which complete
 * made the order, and an earlier revision is superseded by the re-quote that issued the next key (7.4). A forwarded
 * complete under a key that is no intent's (the last such, if no key is) is reported as such.
 */
public final class I2OrderMatchesApproval implements InvariantCheck {

    @Override
    public String id() {
        return "I2";
    }

    @Override
    public String description() {
        return "every order matches the snapshot the user approved for its key: merchant, session, lines, currency,"
                + " and an amount not above the approved maximum";
    }

    @Override
    public String evidence() {
        return "O, G (lines and currency of each order's session), L (which key produced the order, and when it was"
                + " first sent) and A (the approved snapshot); D is not read";
    }

    @Override
    public List<Violation> check(RunEvidence e, CheckContext c) {
        if (e.aborted().isPresent()) {
            return List.of(Wire.aborted(id(), e));
        }
        List<Violation> v = new ArrayList<>();
        Map<String, Wire.KeyOwner> owners = Wire.keyOwners(e);
        List<LogEntry> completes = Wire.completes(e);
        for (GroundTruth.Order order : e.orders()) {
            String where = "order " + order.id() + " on " + order.checkoutId();
            Optional<String> key = orderKey(completes, order.checkoutId(), owners);
            if (key.isEmpty()) {
                v.add(new Violation(id(), where + ": no complete for that session reached the merchant"));
                continue;
            }
            Wire.KeyOwner owner = owners.get(key.get());
            if (owner == null || owner.op() != MutatingOp.COMPLETE) {
                v.add(new Violation(id(), where + ": completed under key " + key.get()
                        + ", which is no intent's complete key"));
                continue;
            }
            LogEntry first = completes.stream()
                    .filter(l -> l.idempotencyKey().equals(key)).findFirst().orElseThrow();
            Optional<AutoApprover.Decision> approval = Wire.decisions(e, owner.intent()).stream()
                    .filter(d -> d.approved() && !d.at().isAfter(first.at()))
                    .reduce((a, b) -> b);
            if (approval.isEmpty()) {
                v.add(new Violation(id(), where + ": the user approved nothing before " + Wire.describe(first)));
                continue;
            }
            ApprovalSnapshot snap = approval.get().snapshot();
            String approved = "approval #" + approval.get().seq() + " of " + owner.intent().value();
            if (!snap.merchantId().equals(c.merchant())) {
                v.add(new Violation(id(), where + ": " + approved + " names merchant " + snap.merchantId().value()
                        + ", but the order is at " + c.merchant().value()));
            }
            if (!snap.sessionId().value().equals(order.checkoutId())) {
                v.add(new Violation(id(), where + ": " + approved + " is for session " + snap.sessionId().value()));
            }
            if (order.amountMinor() > snap.maxTotalMinor()) {
                v.add(new Violation(id(), where + ": amount " + order.amountMinor() + " is above the approved maximum "
                        + snap.maxTotalMinor()));
            }
            GroundTruth.Session session = e.sessions().get(order.checkoutId());
            if (session == null) {
                v.add(new Violation(id(), where + ": the merchant has no session " + order.checkoutId()
                        + ", so its lines cannot be compared"));
                continue;
            }
            List<String> held = session.lines().stream().map(l -> l.itemId() + "x" + l.quantity()).sorted().toList();
            List<String> want = snap.lines().stream().map(l -> l.itemId() + "x" + l.quantity()).sorted().toList();
            if (!held.equals(want)) {
                v.add(new Violation(id(), where + ": the merchant holds " + held + ", but " + approved + " is for "
                        + want));
            }
            if (!session.currency().equals(snap.currency())) {
                v.add(new Violation(id(), where + ": the merchant's currency is " + session.currency() + ", but "
                        + approved + " is in " + snap.currency()));
            }
        }
        return v;
    }

    private static Optional<String> orderKey(List<LogEntry> completes, String session,
                                             Map<String, Wire.KeyOwner> owners) {
        List<String> forwarded = completes.stream()
                .filter(l -> l.forwarded() && Wire.session(l.path()).equals(Optional.of(session)))
                .flatMap(l -> l.idempotencyKey().stream())
                .toList();
        Optional<String> highest = forwarded.stream()
                .filter(k -> owners.containsKey(k) && owners.get(k).op() == MutatingOp.COMPLETE)
                .max(Comparator.comparingInt(k -> owners.get(k).revision()));
        return highest.or(() -> forwarded.isEmpty() ? Optional.empty() : Optional.of(forwarded.getLast()));
    }
}
