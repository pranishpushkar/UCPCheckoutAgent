package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.AWAITING_APPROVAL;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The text of the re-quote policy decisions (9.1 P7, P8), which is shown to people: each is stored as a
 * {@code policy_decision} event of the intent and listed by {@code GET /api/intents/{id}/events} (23.2), the list the
 * console's intent page shows (24.2). A price change at complete is re-quoted once; the events say which re-quote it is
 * and that the old approval no longer covers the new total.
 */
class RequotePolicyEventsTest {

    private final Lab lab = new Lab();
    private final Lab.Worker worker = lab.worker("worker-a");

    private List<String> policyDecisions(IntentId id) {
        return lab.events(id).stream().filter(e -> e.type().equals("policy_decision")).map(IntentEvent::payloadJson).toList();
    }

    // Covers: P7, P8, 7.2, 23.2. Kills RequotePolicy.evaluateRequoteCap:35 MathMutator (requoteCount + 1 becomes
    // requoteCount - 1) and RequoteOutcome.ApprovalRequired.reason:26 EmptyObjectReturnValsMutator (the reason becomes ""):
    // the first re-quote says "re-quote 1 of 2", and the new quote says why a new approval is required, with both totals
    @Test
    void aRequote_isExplainedInThePolicyEventsTheUserCanRead() {
        IntentId id = lab.propose(worker, TWO_SHIRTS).intentId();
        assertThat(lab.decide(worker, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        lab.merchant.setPrice("item_123", 2600);

        assertThat(lab.drive(worker, id)).isEqualTo(AWAITING_APPROVAL);

        List<String> decisions = policyDecisions(id);
        assertThat(decisions).anySatisfy(p -> assertThat(p).contains("\"stage\":\"requote\"").contains("\"allowed\":true")
                .contains("\"reason\":\"re-quote 1 of 2\""));
        assertThat(decisions).anySatisfy(p -> assertThat(p).contains("\"rule\":\"P7\"")
                .contains("\"reason\":\"re-quoted total 5616 replaces the approved total 5400; a new approval is required\""));
    }
}
