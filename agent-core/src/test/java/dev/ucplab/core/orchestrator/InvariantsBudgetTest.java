package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.IntentId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The budget clause of I3 in the property tests' checker (14): no intent is approved above its budget. */
class InvariantsBudgetTest {

    private final Lab lab = new Lab();
    private final Lab.Worker w = lab.worker("worker-a");

    private Observations approvedUnder(Optional<Budget> budget) {
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        PendingApproval pending = lab.pending(w, id);
        Observations.Decision approval = new Observations.Decision(1, lab.clock.instant(), id, true,
                pending.snapshot(), pending.snapshotHash(), false, budget);
        return new Observations(Map.of(0, id), List.of(), List.of(), List.of(), List.of(approval), List.of(), List.of(),
                List.of(), OrchestratorConfig.defaults(), Map.of(), Observations.OwnStore.NONE, Map.of());
    }

    // Covers: I3, P9, 14 (an approval of a 5400 USD snapshot under a 5399 USD budget is an I3 violation)
    @Test
    void i3_flagsAnApprovalAboveTheIntentsBudget() {
        assertThat(Invariants.i3(approvedUnder(Optional.of(new Budget(5_399, "USD")))))
                .singleElement().asString().startsWith("I3:").contains("budget").contains("5399").contains("5400");
    }

    // Covers: I3, P9, 14 (an approval in another currency than the budget's is an I3 violation)
    @Test
    void i3_flagsAnApprovalInAnotherCurrencyThanTheBudget() {
        assertThat(Invariants.i3(approvedUnder(Optional.of(new Budget(1_000_000, "EUR"))))).singleElement()
                .asString().contains("budget");
    }

    // Covers: I3, P9, 14 (an approval at exactly the budget, or with no budget, is not a violation)
    @Test
    void i3_acceptsAnApprovalAtTheBudget_orWithoutOne() {
        assertThat(Invariants.i3(approvedUnder(Optional.of(new Budget(5_400, "USD"))))).isEmpty();
        assertThat(Invariants.i3(approvedUnder(Optional.empty()))).isEmpty();
    }
}
