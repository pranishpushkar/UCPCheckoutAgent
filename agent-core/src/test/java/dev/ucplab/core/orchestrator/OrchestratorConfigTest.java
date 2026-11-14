package dev.ucplab.core.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * An invalid orchestrator configuration fails startup with a message that says which rule it broke (7.5, 7.6): the operator
 * reads it in the startup log. Each rule is checked on both sides of its edge, by its own message, so a setting that is wrong
 * in one way is never reported as another, or let through. Found unchecked by the H3 P7 mutation pass.
 */
class OrchestratorConfigTest {

    private static final Duration S = Duration.ofSeconds(1);

    private static OrchestratorConfig of(
            Duration lease, Duration timeout, Duration quiet, Duration deadline, int resends, Duration pollInitial,
            Duration pollMax, double jitter) {
        return new OrchestratorConfig(lease, timeout, quiet, deadline, resends, pollInitial, pollMax, jitter);
    }

    private static OrchestratorConfig valid() {
        return OrchestratorConfig.defaults();
    }

    // Covers: 7.5. Kills OrchestratorConfig.<init>:35 area (the quiet period is not shorter than the complete timeout):
    // shorter is refused, equal is allowed
    @Test
    void theQuietPeriod_isNotShorterThanTheCompleteTimeout() {
        assertThatThrownBy(() -> of(valid().lease(), S.multipliedBy(10), S.multipliedBy(10).minusNanos(1), valid().reconcileDeadline(),
                2, valid().pollInitial(), valid().pollMax(), 0.2)).hasMessageContaining("resendQuietPeriod");
        assertThatCode(() -> of(valid().lease(), S.multipliedBy(10), S.multipliedBy(10), valid().reconcileDeadline(),
                2, valid().pollInitial(), valid().pollMax(), 0.2)).doesNotThrowAnyException();
    }

    // Covers: 7.6. Kills OrchestratorConfig.<init>:40 ConditionalsBoundary: the lease is at least twice the longest request
    // timeout plus five seconds: a nanosecond less is refused, the minimum itself is allowed
    @Test
    void theLease_isAtLeastTwiceTheLongestTimeoutPlusFiveSeconds() {
        Duration min = S.multipliedBy(25);
        assertThatThrownBy(() -> of(min.minusNanos(1), S.multipliedBy(10), S.multipliedBy(20), S.multipliedBy(60), 2,
                valid().pollInitial(), valid().pollMax(), 0.2)).hasMessageContaining("7.6: lease");
        assertThatCode(() -> of(min, S.multipliedBy(10), S.multipliedBy(20), S.multipliedBy(60), 2, valid().pollInitial(),
                valid().pollMax(), 0.2)).doesNotThrowAnyException();
    }

    // Covers: 7.5. Kills OrchestratorConfig.<init>:44 RemoveConditional_EQUAL_ELSE: a deadline of zero or less is refused as such
    @Test
    void theDeadline_mustBePositive() {
        for (Duration deadline : new Duration[] {Duration.ZERO, S.negated()}) {
            assertThatThrownBy(() -> of(valid().lease(), valid().completeRequestTimeout(), valid().resendQuietPeriod(), deadline, 0,
                    valid().pollInitial(), valid().pollMax(), 0.2)).hasMessageContaining("reconcileDeadline must be positive");
        }
    }

    // Covers: 7.5. Kills OrchestratorConfig.<init>:47 ConditionalsBoundary and RemoveConditional_ORDER_ELSE: a negative number of
    // resends is refused as such, none is allowed
    @Test
    void theResends_areNotNegative() {
        assertThatThrownBy(() -> of(valid().lease(), valid().completeRequestTimeout(), valid().resendQuietPeriod(),
                valid().reconcileDeadline(), -1, valid().pollInitial(), valid().pollMax(), 0.2))
                .hasMessageContaining("maxCompleteResends must not be negative");
        assertThatCode(() -> of(valid().lease(), valid().completeRequestTimeout(), valid().resendQuietPeriod(),
                valid().reconcileDeadline(), 0, valid().pollInitial(), valid().pollMax(), 0.2)).doesNotThrowAnyException();
    }

    // Covers: 7.5. Kills the deadline-versus-resends rule of OrchestratorConfig.<init>:51: the deadline must be greater than the
    // resends times the quiet period (or the cap can never be reached): equal is refused, a nanosecond more is allowed
    @Test
    void theDeadline_isGreaterThanTheResendSpan() {
        Duration span = S.multipliedBy(40);
        assertThatThrownBy(() -> of(valid().lease(), valid().completeRequestTimeout(), valid().resendQuietPeriod(), span, 2,
                valid().pollInitial(), valid().pollMax(), 0.2)).hasMessageContaining("must be greater than");
        assertThatCode(() -> of(valid().lease(), valid().completeRequestTimeout(), valid().resendQuietPeriod(), span.plusNanos(1), 2,
                valid().pollInitial(), valid().pollMax(), 0.2)).doesNotThrowAnyException();
    }

    // Covers: 7.5. Kills OrchestratorConfig.<init>:56 ConditionalsBoundary and RemoveConditional_EQUAL_IF / ORDER: 0 < the initial
    // poll delay <= the largest: zero, and a largest below the initial, are refused; equal is allowed
    @Test
    void thePollDelays_areOrdered() {
        assertThatThrownBy(() -> of(valid().lease(), valid().completeRequestTimeout(), valid().resendQuietPeriod(),
                valid().reconcileDeadline(), 2, Duration.ZERO, S, 0.2)).hasMessageContaining("need 0 < pollInitial");
        assertThatThrownBy(() -> of(valid().lease(), valid().completeRequestTimeout(), valid().resendQuietPeriod(),
                valid().reconcileDeadline(), 2, S, S.minusNanos(1), 0.2)).hasMessageContaining("need 0 < pollInitial");
        assertThatCode(() -> of(valid().lease(), valid().completeRequestTimeout(), valid().resendQuietPeriod(),
                valid().reconcileDeadline(), 2, S, S, 0.2)).doesNotThrowAnyException();
    }

    // Covers: 7.5. Kills OrchestratorConfig.<init>:59 ConditionalsBoundary and RemoveConditional_ORDER_IF / ORDER_ELSE: the jitter is
    // in [0, 1): below zero, one, and not a number are refused; zero and just under one are allowed
    @Test
    void theJitter_isAtLeastZeroAndBelowOne() {
        for (double bad : new double[] {-0.001, 1.0, 1.5, Double.NaN}) {
            assertThatThrownBy(() -> of(valid().lease(), valid().completeRequestTimeout(), valid().resendQuietPeriod(),
                    valid().reconcileDeadline(), 2, valid().pollInitial(), valid().pollMax(), bad))
                    .as(Double.toString(bad)).hasMessageContaining("pollJitter must be in [0, 1)");
        }
        for (double ok : new double[] {0.0, 0.999}) {
            assertThat(of(valid().lease(), valid().completeRequestTimeout(), valid().resendQuietPeriod(), valid().reconcileDeadline(),
                    2, valid().pollInitial(), valid().pollMax(), ok).pollJitter()).isEqualTo(ok);
        }
    }
}
