package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** H3 P8: every rule of the client settings (8.2, 8.3), at its edge, per mutant. */
class UcpConfigMutationTest {

    private static final String AGENT = "profile=\"https://agent.example/.well-known/ucp\"";
    private static final Duration S1 = Duration.ofSeconds(1);

    private static UcpConfig config(Duration connect, Duration request, Duration complete, int retries, Duration initial,
            Duration cap, int threshold, Duration openFor, int maxBytes, int burst, double rate) {
        return new UcpConfig(AGENT, connect, request, complete, retries, initial, cap, threshold, openFor, maxBytes, burst, rate);
    }

    private static UcpConfig valid() {
        return config(S1, S1, S1, 0, S1, S1, 1, S1, 1, 1, Double.MIN_VALUE);
    }

    private static void refused(Runnable build, String message) {
        assertThatThrownBy(build::run).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    // Covers: 8.2, 8.3. Kills UcpConfig.<init> ConditionalsBoundary (:60, :63, :69, :72, :75) and RemoveConditional_ORDER_ELSE (:72):
    // every limit is accepted exactly at its edge and refused just beyond it
    @Test
    void everyLimit_isAcceptedAtItsEdge_andRefusedBeyondIt() {
        assertThat(valid().maxRetries()).isZero();
        assertThat(valid().breakerThreshold()).isOne();

        refused(() -> config(S1, S1, Duration.ofMillis(999), 0, S1, S1, 1, S1, 1, 1, 1), "completeTimeout must not be shorter");
        refused(() -> config(S1, S1, S1, 0, S1, Duration.ofMillis(999), 1, S1, 1, 1, 1), "backoffCap must not be shorter");
        refused(() -> config(S1, S1, S1, -1, S1, S1, 1, S1, 1, 1, 1), "maxRetries must not be negative");
        refused(() -> config(S1, S1, S1, 0, S1, S1, 0, S1, 1, 1, 1), "breakerThreshold must be at least 1");
        refused(() -> config(S1, S1, S1, 0, S1, S1, -1, S1, 1, 1, 1), "breakerThreshold must be at least 1");
        refused(() -> config(S1, S1, S1, 0, S1, S1, 1, S1, 0, 1, 1), "maxResponseBytes must be at least 1");
        refused(() -> config(S1, S1, S1, 0, S1, S1, 1, S1, -1, 1, 1), "maxResponseBytes must be at least 1");
        refused(() -> config(S1, S1, S1, 0, S1, S1, 1, S1, 1, 0, 1), "rateBurst must be at least 1");
        refused(() -> config(S1, S1, S1, 0, S1, S1, 1, S1, 1, -1, 1), "rateBurst must be at least 1");
        refused(() -> config(S1, S1, S1, 0, S1, S1, 1, S1, 1, 1, 0), "ratePerSecond must be positive");
        refused(() -> config(S1, S1, S1, 0, S1, S1, 1, S1, 1, 1, -0.5), "ratePerSecond must be positive");
        refused(() -> config(S1, S1, S1, 0, S1, S1, 1, S1, 1, 1, Double.NaN), "ratePerSecond must be positive");
    }

    // Covers: 8.2, 8.3. Kills UcpConfig.<init>:55 to :59 VoidMethodCall (requirePositive of each duration) and
    // requirePositive:98 RemoveConditional: a zero, a negative or a missing duration is refused, field by field
    @Test
    void everyDuration_mustBePositive() {
        Duration zero = Duration.ZERO;
        Duration negative = Duration.ofMillis(-1);
        for (Duration bad : new Duration[] {zero, negative}) {
            refused(() -> config(bad, S1, S1, 0, S1, S1, 1, S1, 1, 1, 1), "connectTimeout must be positive");
            refused(() -> config(S1, bad, S1, 0, S1, S1, 1, S1, 1, 1, 1), "requestTimeout must be positive");
            refused(() -> config(S1, S1, bad, 0, S1, S1, 1, S1, 1, 1, 1), "completeTimeout must be positive");
            refused(() -> config(S1, S1, S1, 0, bad, S1, 1, S1, 1, 1, 1), "backoffInitial must be positive");
            refused(() -> config(S1, S1, S1, 0, S1, bad, 1, S1, 1, 1, 1), "backoffCap must be positive");
            refused(() -> config(S1, S1, S1, 0, S1, S1, 1, bad, 1, 1, 1), "breakerOpenFor must be positive");
        }
        assertThatThrownBy(() -> config(null, S1, S1, 0, S1, S1, 1, S1, 1, 1, 1)).isInstanceOf(NullPointerException.class).hasMessage("connectTimeout");
        assertThatThrownBy(() -> config(S1, null, S1, 0, S1, S1, 1, S1, 1, 1, 1)).isInstanceOf(NullPointerException.class).hasMessage("requestTimeout");
        assertThatThrownBy(() -> config(S1, S1, null, 0, S1, S1, 1, S1, 1, 1, 1)).isInstanceOf(NullPointerException.class).hasMessage("completeTimeout");
        assertThatThrownBy(() -> config(S1, S1, S1, 0, null, S1, 1, S1, 1, 1, 1)).isInstanceOf(NullPointerException.class).hasMessage("backoffInitial");
        assertThatThrownBy(() -> config(S1, S1, S1, 0, S1, null, 1, S1, 1, 1, 1)).isInstanceOf(NullPointerException.class).hasMessage("backoffCap");
        assertThatThrownBy(() -> config(S1, S1, S1, 0, S1, S1, 1, null, 1, 1, 1)).isInstanceOf(NullPointerException.class).hasMessage("breakerOpenFor");
        assertThat(config(Duration.ofNanos(1), Duration.ofNanos(1), Duration.ofNanos(1), 0, Duration.ofNanos(1), Duration.ofNanos(1), 1,
                Duration.ofNanos(1), 1, 1, 1).connectTimeout()).isEqualTo(Duration.ofNanos(1));
    }

    // Covers: 8.2. The agent header is a name: null and blank are refused
    @Test
    void theAgentHeader_isNotBlank() {
        assertThatThrownBy(() -> new UcpConfig(null, S1, S1, S1, 0, S1, S1, 1, S1, 1, 1, 1)).isInstanceOf(NullPointerException.class);
        refused(() -> new UcpConfig(" ", S1, S1, S1, 0, S1, S1, 1, S1, 1, 1, 1), "ucpAgent must not be blank");
    }
}
