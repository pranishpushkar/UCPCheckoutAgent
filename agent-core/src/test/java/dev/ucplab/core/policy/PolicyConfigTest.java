package dev.ucplab.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.config.AgentProfile;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyConfigTest {

    private static PolicyConfig withApproval(boolean approvalRequired) {
        return new PolicyConfig(20_000, 50_000, 5, 5, 10, approvalRequired, 2);
    }

    // Covers: P2, P3, P4, P5, P6, P8 (defaults of table 9.1)
    @Test
    void policyConfig_defaults_matchTable91() {
        assertThat(PolicyConfig.defaults()).isEqualTo(new PolicyConfig(20_000, 50_000, 5, 5, 10, true, 2));
    }

    // Covers: P7 (always on: the configuration has no P7 setting)
    @Test
    void policyConfig_hasNoP7Setting() {
        assertThat(PolicyConfig.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("perPurchaseCapMinor", "dailyCapMinor", "maxQuantityPerLine", "maxLines",
                        "maxProposalsPerHour", "approvalRequired", "maxRequotes");
    }

    // Covers: P6 (approval may not be false in the prod profile)
    @Test
    void requireValidFor_throws_whenApprovalOffInProd() {
        assertThatThrownBy(() -> withApproval(false).requireValidFor(AgentProfile.PROD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("P6");
    }

    // Covers: P6 (approval may be switched off outside prod, and is always allowed on)
    @Test
    void requireValidFor_accepts_otherCombinations() {
        assertThatCode(() -> withApproval(false).requireValidFor(AgentProfile.LOCAL)).doesNotThrowAnyException();
        assertThatCode(() -> withApproval(false).requireValidFor(AgentProfile.DEMO)).doesNotThrowAnyException();
        assertThatCode(() -> withApproval(true).requireValidFor(AgentProfile.PROD)).doesNotThrowAnyException();
    }

    // Covers: P2 to P5, P8 (limits must be usable values)
    @Test
    void policyConfig_throws_forNonPositiveLimits() {
        assertThatThrownBy(() -> new PolicyConfig(0, 50_000, 5, 5, 10, true, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyConfig(20_000, 50_000, 0, 5, 10, true, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyConfig(20_000, 50_000, 5, 5, 10, true, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: section 15 (AGENT_PROFILE values)
    @Test
    void agentProfile_parse_acceptsConfiguredValues() {
        assertThat(AgentProfile.parse("prod")).isEqualTo(AgentProfile.PROD);
        assertThat(AgentProfile.parse(" Local ")).isEqualTo(AgentProfile.LOCAL);
        assertThatThrownBy(() -> AgentProfile.parse("staging")).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 9.1 (PolicyDecision{allowed, ruleId, reason}: a denial names a rule, an allow does not)
    @Test
    void policyDecision_throws_whenAllowedAndRuleIdDisagree() {
        assertThatThrownBy(() -> new PolicyDecision(true, Optional.of(PolicyRule.P1), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyDecision(false, Optional.empty(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
