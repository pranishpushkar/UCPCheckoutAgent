package dev.ucplab.eval.check;

import dev.ucplab.eval.RunEvidence;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every check the harness runs. All six run on every run (they are cheap); the scenario's required invariants decide
 * which the report lists as required and which as "also checked" (m4-plan section 5).
 */
public final class Checks {

    private static final List<InvariantCheck> ALL = List.of(new I1OneOrder(), new I2OrderMatchesApproval(),
            new I3CompleteNeedsApproval(), new I4SessionFixed(), new I5Liveness(), new I7SameKeySameBody());

    private Checks() {
    }

    public static List<InvariantCheck> all() {
        return ALL;
    }

    public static Optional<InvariantCheck> byId(String id) {
        return ALL.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /**
     * Every check on the run. A run over several merchants ({@link RunEvidence#views()}) is checked one merchant at a time:
     * session and order ids are each merchant's own, so a slice holds one merchant's side and the checks, which know one,
     * run on each; a violation says which merchant's side it is on.
     */
    public static CheckResult run(RunEvidence evidence, CheckContext context) {
        if (evidence.views().isEmpty()) {
            return runOne(evidence, context);
        }
        Map<String, List<Violation>> out = new LinkedHashMap<>();
        ALL.forEach(check -> out.put(check.id(), new ArrayList<>()));
        for (RunEvidence.MerchantView view : evidence.views()) {
            CheckResult one = runOne(evidence.slice(view), context.withMerchant(view.merchant()));
            one.byInvariant().forEach((id, found) -> found.forEach(v -> out.get(id).add(
                    new Violation(v.invariant(), "at " + view.merchant().value() + ": " + v.detail()))));
        }
        return new CheckResult(out);
    }

    private static CheckResult runOne(RunEvidence evidence, CheckContext context) {
        Map<String, List<Violation>> out = new LinkedHashMap<>();
        for (InvariantCheck check : ALL) {
            out.put(check.id(), check.check(evidence, context));
        }
        return new CheckResult(out);
    }
}
