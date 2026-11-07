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
        RequotePolicy none = new RequotePolicy(new PolicyConfig(20_000, 50_000, 5, 5, 10, true, true, 0));

        assertThat(none.evaluateRequoteCap(0).ruleId()).contains(PolicyRule.P8);
    }

    // Covers: P8 (a negative count is a programming error)
    @Test
    void evaluateRequoteCap_throws_forNegativeCount() {
        assertThatThrownBy(() -> policy.evaluateRequoteCap(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: P7, I2 (a higher re-quoted total needs a new approval; equal or lower is covered)
    @Test
    void evaluateRequotedTotal_deniesWithP7_whenTotalHigher() {
        assertThat(policy.evaluateRequotedTotal(6696, 6697).ruleId()).contains(PolicyRule.P7);
        assertThat(policy.evaluateRequotedTotal(6696, 6696).allowed()).isTrue();
        assertThat(policy.evaluateRequotedTotal(6696, 5000).allowed()).isTrue();
    }

    // Covers: P7 (with re-approval off the rule does not deny)
    @Test
    void evaluateRequotedTotal_allows_whenReapprovalOff() {
        RequotePolicy off = new RequotePolicy(new PolicyConfig(20_000, 50_000, 5, 5, 10, true, false, 2));

        assertThat(off.evaluateRequotedTotal(6696, 9000).allowed()).isTrue();
    }
}
