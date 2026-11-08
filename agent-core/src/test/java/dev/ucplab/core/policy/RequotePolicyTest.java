package dev.ucplab.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.intent.TransitionGuard;
import org.junit.jupiter.api.Test;

class RequotePolicyTest {

    private final RequotePolicy policy = new RequotePolicy(PolicyConfig.defaults());

    // Covers: P8, S16, table 7.2 (cap 2: re-quotes at counts 0 and 1 allowed, the third is denied with price_unstable)
    @Test
    void evaluateRequoteCap_deniesWithP8_whenCapReached() {
        assertThat(policy.evaluateRequoteCap(0).allowed()).isTrue();
        assertThat(policy.evaluateRequoteCap(1).allowed()).isTrue();

        PolicyDecision d = policy.evaluateRequoteCap(2);
        assertThat(d.ruleId()).contains(PolicyRule.P8);
        assertThat(d.reason()).isEqualTo("price_unstable");
    }

    // Covers: P8, table 7.2 (the guard selects REQUOTE_BELOW_CAP or REQUOTE_CAP_REACHED)
    @Test
    void requoteGuard_followsP8Cap() {
        assertThat(policy.requoteGuard(1)).isEqualTo(TransitionGuard.REQUOTE_BELOW_CAP);
        assertThat(policy.requoteGuard(2)).isEqualTo(TransitionGuard.REQUOTE_CAP_REACHED);
        assertThat(policy.requoteGuard(3)).isEqualTo(TransitionGuard.REQUOTE_CAP_REACHED);
    }

    // Covers: P8 (a cap of 0 forbids any re-quote)
    @Test
    void evaluateRequoteCap_deniesFirstRequote_whenCapIsZero() {
        RequotePolicy none = new RequotePolicy(new PolicyConfig(20_000, 50_000, 5, 5, 10, true, 0));

        assertThat(none.evaluateRequoteCap(0).ruleId()).contains(PolicyRule.P8);
    }

    // Covers: P8 (a negative count is a programming error)
    @Test
    void evaluateRequoteCap_throws_forNegativeCount() {
        assertThatThrownBy(() -> policy.evaluateRequoteCap(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: P7, P8, I3, table 7.2 (a re-quote below the cap needs a new approval and is not rejected)
    @Test
    void evaluateReapproval_requiresApproval_belowCap() {
        for (int count = 0; count < 2; count++) {
            RequoteOutcome outcome = policy.evaluateReapproval(count, 6696, 6900);

            assertThat(outcome).isEqualTo(new RequoteOutcome.ApprovalRequired(6696, 6900));
            assertThat(outcome).isNotInstanceOf(RequoteOutcome.Rejected.class);
            assertThat(outcome.rule()).isEqualTo(PolicyRule.P7);
        }
    }

    // Covers: P8, S16, table 7.2 (the re-quote that hits the cap is rejected with price_unstable)
    @Test
    void evaluateReapproval_rejectsWithPriceUnstable_atCap() {
        RequoteOutcome outcome = policy.evaluateReapproval(2, 6696, 6900);

        assertThat(outcome).isEqualTo(new RequoteOutcome.Rejected(2, 2));
        assertThat(outcome.rule()).isEqualTo(PolicyRule.P8);
        assertThat(outcome.reason()).isEqualTo("price_unstable");
    }

    // Covers: P7, I2, I3 (approval is required whether the price went up, down or stayed the same)
    @Test
    void evaluateReapproval_requiresApproval_whateverTheDirectionOfThePrice() {
        assertThat(policy.evaluateReapproval(0, 6696, 6697)).isInstanceOf(RequoteOutcome.ApprovalRequired.class);
        assertThat(policy.evaluateReapproval(0, 6696, 6696)).isInstanceOf(RequoteOutcome.ApprovalRequired.class);
        assertThat(policy.evaluateReapproval(0, 6696, 5000)).isInstanceOf(RequoteOutcome.ApprovalRequired.class);
    }

    // Covers: P7 (always on: even with approval off and lax limits, a re-quote needs a new approval)
    @Test
    void evaluateReapproval_requiresApproval_underAnyConfiguration() {
        RequotePolicy lax = new RequotePolicy(new PolicyConfig(1_000_000, 1_000_000, 99, 99, 999, false, 10));

        assertThat(lax.evaluateReapproval(0, 6696, 5000)).isInstanceOf(RequoteOutcome.ApprovalRequired.class);
    }

    // Covers: P8 (a negative count is a programming error here too)
    @Test
    void evaluateReapproval_throws_forNegativeCount() {
        assertThatThrownBy(() -> policy.evaluateReapproval(-1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
