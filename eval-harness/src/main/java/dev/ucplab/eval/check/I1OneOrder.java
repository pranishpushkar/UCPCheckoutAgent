package dev.ucplab.eval.check;

import dev.ucplab.eval.GroundTruth;
import dev.ucplab.eval.RunEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * I1: an intent produces at most one order. Every M4 run holds one basket, so the run as a whole may hold at most one
 * order, and the merchant's own view of that order's session must be {@code completed} with that order id.
 */
public final class I1OneOrder implements InvariantCheck {

    @Override
    public String id() {
        return "I1";
    }

    @Override
    public String description() {
        return "at most one order per run, and each order's session is completed with that order";
    }

    @Override
    public String evidence() {
        return "O (/_lab/orders) and G (the merchant's GET of each order's session); D is not read";
    }

    @Override
    public List<Violation> check(RunEvidence e, CheckContext c) {
        if (e.aborted().isPresent()) {
            return List.of(Wire.aborted(id(), e));
        }
        List<Violation> v = new ArrayList<>();
        if (e.orders().size() > 1) {
            v.add(new Violation(id(), "the merchant holds " + e.orders().size() + " orders for the run: "
                    + e.orders().stream().map(o -> o.id() + " on " + o.checkoutId()).toList()));
        }
        for (GroundTruth.Order order : e.orders()) {
            GroundTruth.Session s = e.sessions().get(order.checkoutId());
            if (s == null) {
                v.add(new Violation(id(), "order " + order.id() + ": the merchant has no session "
                        + order.checkoutId()));
            } else if (!s.status().equals("completed") || !s.orderId().equals(Optional.of(order.id()))) {
                v.add(new Violation(id(), "order " + order.id() + ": session " + s.id() + " is " + s.status()
                        + " with order " + s.orderId().orElse("none")));
            }
        }
        return v;
    }
}
