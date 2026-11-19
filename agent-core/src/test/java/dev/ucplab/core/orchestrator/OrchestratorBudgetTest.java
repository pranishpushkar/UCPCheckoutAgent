package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The user's budget at the orchestrator (9.1 P9, 7.7, I3). Two red t-shirts quote at 5400 USD. */
class OrchestratorBudgetTest {

    private Lab lab = new Lab();
    private Lab.Worker w = lab.worker("worker-a");

    private List<String> events(IntentId id, String type) {
        return lab.events(id).stream().filter(e -> e.type().equals(type)).map(IntentEvent::payloadJson).toList();
    }

    // Covers: P9, 9.1, FR7, I3 (a quote one minor unit above the budget ends FAILED_NOT_CHARGED with policy_P9: no
    // approval is opened, the session cancel is owed, and the denial is recorded with its rule)
    @Test
    void aQuoteAboveTheBudget_failsNotCharged_withPolicyP9_andNoApproval() {
        ProposalResult r = lab.proposeWithin(w, TWO_SHIRTS, new Budget(5_399, "USD"));

        assertThat(r.state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(r.intentId()).failureReason()).contains("policy_P9");
        assertThat(lab.intent(r.intentId()).cancelPending()).isTrue();
        assertThat(lab.approvals.findLatestForSystem(r.intentId())).isEmpty();
        assertThat(events(r.intentId(), "policy_decision")).anySatisfy(e -> assertThat(e)
                .contains("\"stage\":\"quote\"").contains("\"allowed\":false").contains("\"rule\":\"P9\""));
        assertThat(lab.drive(w, r.intentId())).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: P9, 9.1 (exact: a quote equal to the budget opens the approval as usual; the intent keeps the budget)
    @Test
    void aQuoteEqualToTheBudget_opensTheApproval_andTheIntentKeepsTheBudget() {
        ProposalResult r = lab.proposeWithin(w, TWO_SHIRTS, new Budget(5_400, "USD"));

        assertThat(r.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(lab.intent(r.intentId()).budget()).contains(new Budget(5_400, "USD"));
        assertThat(lab.pending(w, r.intentId()).snapshot().totalMinor()).isEqualTo(5_400);
    }

    // Covers: P9, 9.1 (a budget in another currency than the quote's is refused, however large)
    @Test
    void aBudgetInAnotherCurrency_isRefused() {
        ProposalResult r = lab.proposeWithin(w, TWO_SHIRTS, new Budget(1_000_000, "EUR"));

        assertThat(r.state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(r.intentId()).failureReason()).contains("policy_P9");
    }

    // Covers: P9, 9.1, I3 (P9 is checked at every quote: a re-quote after approval that rises above the budget ends
    // FAILED_NOT_CHARGED with policy_P9, and no order is placed above it)
    @Test
    void aRequoteAboveTheBudget_afterApproval_failsNotCharged_withNoOrder() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        IntentId id = lab.proposeWithin(w, TWO_SHIRTS, new Budget(5_400, "USD")).intentId();
        lab.decide(w, id, true);

        assertThat(lab.drive(w, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("policy_P9");
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: 7.7, P9 (with no budget the dedupe key is the key before V11; a budget is part of it)
    @Test
    void theDedupeKey_isUnchangedWithoutABudget_andCoversTheBudgetWhenSet() {
        UserId alice = new UserId("alice");
        MerchantId shop = new MerchantId("shop");
        String before = DefaultOrchestrator.dedupeKey(alice, shop, TWO_SHIRTS);

        assertThat(DefaultOrchestrator.dedupeKey(alice, shop, TWO_SHIRTS, Optional.empty())).isEqualTo(before);
        String fifty = DefaultOrchestrator.dedupeKey(alice, shop, TWO_SHIRTS, Optional.of(new Budget(5_000, "USD")));
        assertThat(fifty).isNotEqualTo(before)
                .isNotEqualTo(DefaultOrchestrator.dedupeKey(alice, shop, TWO_SHIRTS, Optional.of(new Budget(6_000, "USD"))))
                .isNotEqualTo(DefaultOrchestrator.dedupeKey(alice, shop, TWO_SHIRTS, Optional.of(new Budget(5_000, "EUR"))))
                .isEqualTo(DefaultOrchestrator.dedupeKey(alice, shop, TWO_SHIRTS, Optional.of(new Budget(5_000, "USD"))));
    }

    // Covers: 7.7, S4, P9 (an identical proposal under the same budget returns the active intent; under another budget it
    // is a new proposal, so a budget is never borrowed from an earlier intent)
    @Test
    void anIdenticalProposal_reusesTheIntentOnlyUnderTheSameBudget() {
        ProposalResult first = lab.proposeWithin(w, TWO_SHIRTS, new Budget(6_000, "USD"));
        ProposalResult same = lab.proposeWithin(w, TWO_SHIRTS, new Budget(6_000, "USD"));
        ProposalResult other = lab.proposeWithin(w, TWO_SHIRTS, new Budget(7_000, "USD"));

        assertThat(same.intentId()).isEqualTo(first.intentId());
        assertThat(other.intentId()).isNotEqualTo(first.intentId());
        assertThat(lab.intent(other.intentId()).budget()).contains(new Budget(7_000, "USD"));
    }
}
