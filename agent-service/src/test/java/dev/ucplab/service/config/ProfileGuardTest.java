package dev.ucplab.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.config.AgentProfile;
import org.junit.jupiter.api.Test;

/** AGENT_PROFILE to exactly one Spring profile (18, 18.8, D10, m6-plan section 4). */
class ProfileGuardTest {

    // Covers: 18.8 (local and prod map to the Spring profile of the same name)
    @Test
    void springProfile_localAndProd_mapByName() {
        assertThat(ProfileGuard.springProfile(AgentProfile.LOCAL)).isEqualTo("local");
        assertThat(ProfileGuard.springProfile(AgentProfile.PROD)).isEqualTo("prod");
    }

    // Covers: D10 (demo is refused in M6)
    @Test
    void springProfile_demo_isRefused() {
        assertThatThrownBy(() -> ProfileGuard.springProfile(AgentProfile.DEMO)).hasMessage(ProfileGuard.DEMO_REFUSED);
    }

    // Covers: 18 (local with prod refuses to start, whichever one AGENT_PROFILE names)
    @Test
    void check_localWithProd_isRefused() {
        String[] both = {"local", "prod"};

        assertThatThrownBy(() -> ProfileGuard.check(AgentProfile.LOCAL, both)).hasMessage(ProfileGuard.MIXED_REFUSED);
        assertThatThrownBy(() -> ProfileGuard.check(AgentProfile.PROD, both)).hasMessage(ProfileGuard.MIXED_REFUSED);
    }

    // Covers: 18, D10 (another agent profile beside the chosen one, or the chosen one missing, is refused; a profile
    // that is not an agent profile is left alone)
    @Test
    void check_onlyTheChosenAgentProfile_isAccepted() {
        assertThatCode(() -> ProfileGuard.check(AgentProfile.LOCAL, new String[] {"local", "extra"}))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ProfileGuard.check(AgentProfile.LOCAL, new String[] {"local", "demo"}))
                .hasMessage(ProfileGuard.OTHER_REFUSED);
        assertThatThrownBy(() -> ProfileGuard.check(AgentProfile.PROD, new String[] {}))
                .hasMessage(ProfileGuard.OTHER_REFUSED);
    }
}
