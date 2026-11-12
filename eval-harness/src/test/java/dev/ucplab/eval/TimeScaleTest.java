package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.orchestrator.OrchestratorConfig;
import dev.ucplab.core.recovery.RecoveryConfig;
import dev.ucplab.ucp.UcpConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TimeScaleTest {

    private static final String AGENT = "profile=\"https://agent.example/.well-known/ucp\"";

    // Covers: 7.5, 7.6, 13.1 (k = 1, 4 and 5 keep every ordering, and the configs they build pass their own checks)
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void timeScale_upToFive_keepsEveryOrdering(int k) {
        TimeScale t = TimeScale.of(k);

        assertThat(t.orderings()).allSatisfy(o -> assertThat(o.holds()).as(o.name()).isTrue());
        UcpConfig ucp = t.ucp(AGENT);
        OrchestratorConfig orchestrator = t.orchestrator();
        RecoveryConfig recovery = t.recovery();
        assertThat(orchestrator.lease()).isEqualTo(recovery.lease());
        assertThat(orchestrator.completeRequestTimeout()).isEqualTo(ucp.completeTimeout());
    }

    // Covers: 7.5, 7.6, 13.1 (k = 6 is refused, and the message names the ordering that breaks: the S9 takeover)
    @Test
    void timeScale_six_isRefused_namingTheS9Ordering() {
        assertThatThrownBy(() -> TimeScale.of(6)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k = 6")
                .hasMessageContaining("S9 takeover (lease + period) inside the reconcile deadline");
    }

    // Covers: 13.1 (k below 1 is not a time scale)
    @Test
    void timeScale_belowOne_isRefused() {
        assertThatThrownBy(() -> TimeScale.of(0)).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 7.5, 7.6, 8.2, 8.3 (k = 1 is exactly the design defaults)
    @Test
    void timeScale_one_isTheDesignDefaults() {
        TimeScale t = TimeScale.of(1);

        assertThat(t.ucp(AGENT)).isEqualTo(UcpConfig.defaults(AGENT));
        assertThat(t.orchestrator()).isEqualTo(OrchestratorConfig.defaults());
        assertThat(t.recovery()).isEqualTo(RecoveryConfig.defaults());
        assertThat(t.s3Hold()).isEqualTo(Duration.ofSeconds(15));
        assertThat(t.s8Outage()).isEqualTo(Duration.ofSeconds(20));
    }

    // Covers: 7.6, m4-plan section 3 (the k = 4 column: the lease is held at its 2 Tc + 5 s floor, not 30/4)
    @Test
    void timeScale_four_matchesThePlanTable() {
        TimeScale t = TimeScale.of(4);
        UcpConfig ucp = t.ucp(AGENT);
        OrchestratorConfig o = t.orchestrator();

        assertThat(ucp.connectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(ucp.requestTimeout()).isEqualTo(Duration.ofMillis(1250));
        assertThat(ucp.completeTimeout()).isEqualTo(Duration.ofMillis(2500));
        assertThat(ucp.backoffInitial()).isEqualTo(Duration.ofMillis(50));
        assertThat(ucp.backoffCap()).isEqualTo(Duration.ofMillis(500));
        assertThat(ucp.breakerOpenFor()).isEqualTo(Duration.ofMillis(7500));
        assertThat(ucp.maxRetries()).isEqualTo(3);
        assertThat(o.resendQuietPeriod()).isEqualTo(Duration.ofSeconds(5));
        assertThat(o.reconcileDeadline()).isEqualTo(Duration.ofSeconds(15));
        assertThat(o.pollInitial()).isEqualTo(Duration.ofNanos(62_500_000));
        assertThat(o.pollMax()).isEqualTo(Duration.ofMillis(500));
        assertThat(o.lease()).isEqualTo(Duration.ofSeconds(10));
        assertThat(o.maxCompleteResends()).isEqualTo(2);
        assertThat(t.workerPeriod()).isEqualTo(Duration.ofMillis(1250));
        assertThat(t.s3Hold()).isEqualTo(Duration.ofMillis(3750));
        assertThat(t.s8Outage()).isEqualTo(Duration.ofSeconds(5));
    }

    // Covers: m4-plan section 3 (the S9 margin is 3.75 s at k = 4 and 2 s at k = 5, as the plan works out)
    @Test
    void timeScale_s9Margin_matchesThePlan() {
        assertThat(margin(4, "S9")).isEqualTo(Duration.ofMillis(3750));
        assertThat(margin(5, "S9")).isEqualTo(Duration.ofSeconds(2));
        assertThat(margin(1, "S9")).isEqualTo(Duration.ofSeconds(25));
    }

    private static Duration margin(int k, String prefix) {
        return TimeScale.of(k).orderings().stream().filter(o -> o.name().startsWith(prefix)).findFirst()
                .orElseThrow().margin();
    }
}
