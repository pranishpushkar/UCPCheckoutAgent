package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.merchant.Money;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** S30 (13.2, 6.4 v9): a follow-up's "nothing cheaper fits" reply needs a search in that turn. */
class UncheckedClaimTest {

    // Covers: S30, 6.4 v9 (the live re-run's reply, and other ways of saying nothing cheaper, nothing else or nothing that
    // fits is available, are recognised)
    @Test
    void saysNothingFits_recognisesTheLiveReplyAndItsKin() {
        for (String reply : List.of(
                "There are no cheaper red Nike shoes available in size UK 7 within your budget of ₹8,000.00.",
                "Nothing fits your budget of $12.00. The closest price I found was $12.96.",
                "I couldn't find anything cheaper than the Blue Mug.",
                "Sorry, there is no other option within your budget.",
                "None of the other mugs are cheaper.",
                "I could not find a cheaper pair in UK 7.",
                "There isn't anything else available in that size.",
                "There is no cheaper mug than the Blue Mug at $12.00 within your budget.")) {
            assertThat(UncheckedClaim.saysNothingFits(reply)).as(reply).isTrue();
        }
    }

    // Covers: S30, 6.4 v9 (a reply that offers something, asks, or only reports a proposal is no such claim)
    @Test
    void saysNothingFits_isFalseForAReplyThatOffersOrAsks() {
        for (String reply : List.of(
                "Here is a cheaper option: the Nike Court Vision Low at ₹6,499.00 from KICKS CREW.",
                "I have asked for your approval of 1 Blue Mug.",
                "Do you want a different colour?",
                "Approve to get the store's link; you buy on the store's site.",
                "To change the budget, set 8000 in the Budget field and send.")) {
            assertThat(UncheckedClaim.saysNothingFits(reply)).as(reply).isFalse();
        }
    }

    // Covers: S30, 6.4 v9, I8 (the row fails only for such a claim in a turn with no search, with a fixed text that
    // quotes nothing of the reply)
    @Test
    void check_failsOnlyAnUnbackedClaim_withAFixedText() {
        String reply = "There are no cheaper mugs available within your budget.";

        assertThat(UncheckedClaim.check(reply, false)).contains(
                "S30 claim: the follow-up said nothing cheaper fits without a search in that turn (unchecked claim)");
        assertThat(UncheckedClaim.check(reply, true)).isEmpty();
        assertThat(UncheckedClaim.check("Here is a cheaper one.", false)).isEmpty();
        assertThat(UncheckedClaim.check(reply, false).orElseThrow()).doesNotContain("mugs");
    }

    // Covers: S30, 6.2, 23.1 PATCH budget (the follow-up's searches carry the changed budget: a search under the
    // intent's old one, or a follow-up with no filtered search at all, fails; the near miss's unfiltered second search is
    // allowed after a filtered one)
    @Test
    void staleBudget_everyFilteredFollowUpSearch_carriesTheChangedBudget() {
        Budget changed = new Budget(1_500, "USD");
        Optional<Money> now = Optional.of(new Money(1_500, "USD"));
        Optional<Money> old = Optional.of(new Money(2_000, "USD"));
        String failure = "S30 claim: a follow-up search did not carry the run's changed budget";

        assertThat(UncheckedClaim.staleBudget(List.of(now), changed)).isEmpty();
        assertThat(UncheckedClaim.staleBudget(List.of(now, Optional.empty()), changed)).as("the near miss").isEmpty();
        assertThat(UncheckedClaim.staleBudget(List.of(old), changed)).contains(failure);
        assertThat(UncheckedClaim.staleBudget(List.of(now, old), changed)).contains(failure);
        assertThat(UncheckedClaim.staleBudget(List.of(Optional.empty()), changed)).contains(failure);
        assertThat(UncheckedClaim.staleBudget(List.of(), changed)).contains(failure);
    }
}
