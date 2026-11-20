package dev.ucplab.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.ModelClientSettings;
import dev.ucplab.runtime.AgentBudget;
import dev.ucplab.service.config.ServiceProperties.AgentModel;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RunConfigurationTest {

    // Covers: 6.5 (a live model call may take 20 s, which the SDK also sends as X-Server-Timeout; 10 s met the API's own
    // deadline, a 504, on a third call of a growing conversation), within the client's 30 s maximum
    @Test
    void callTimeout_is20s() {
        assertThat(RunConfiguration.CALL_TIMEOUT).isEqualTo(Duration.ofSeconds(20));
        assertThat(RunConfiguration.CALL_TIMEOUT).isLessThanOrEqualTo(ModelClientSettings.MAX_CALL_TIMEOUT);
    }

    // Covers: 6.3 (AGENT_MODEL=gemini runs with the live budget, 60 s; the scripted model keeps 30 s)
    @Test
    void budget_isLiveForGemini_andTheDefaultForScripted() {
        assertThat(RunConfiguration.budget(AgentModel.GEMINI)).isEqualTo(AgentBudget.LIVE);
        assertThat(RunConfiguration.budget(AgentModel.GEMINI).wallClock()).isEqualTo(Duration.ofSeconds(60));
        assertThat(RunConfiguration.budget(AgentModel.SCRIPTED)).isEqualTo(AgentBudget.DEFAULT);
        assertThat(RunConfiguration.budget(AgentModel.SCRIPTED).wallClock()).isEqualTo(Duration.ofSeconds(30));
    }
}
