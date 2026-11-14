package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class UcpConfigTest {

    private static final String AGENT = "profile=\"https://agent.example/.well-known/ucp\"";

    // Covers: 8.2, 8.3 (defaults: 2 s connect, 5 s request, 10 s complete, 3 retries, backoff 200 ms to 2 s, breaker 5 and 30 s)
    @Test
    void defaults_matchSections82And83() {
        UcpConfig c = UcpConfig.defaults(AGENT);

        assertThat(c.ucpAgent()).isEqualTo(AGENT);
        assertThat(c.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(c.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(c.completeTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(c.maxRetries()).isEqualTo(3);
        assertThat(c.backoffInitial()).isEqualTo(Duration.ofMillis(200));
        assertThat(c.backoffCap()).isEqualTo(Duration.ofSeconds(2));
        assertThat(c.breakerThreshold()).isEqualTo(5);
        assertThat(c.breakerOpenFor()).isEqualTo(Duration.ofSeconds(30));
    }

    // Covers: 8.3 (the rate limit defaults to a burst of 10 and 5 a second, the older ten-argument form keeps those, and
    // a burst below 1 or a rate that is not positive is refused)
    @Test
    void rateLimit_defaults_andIsValidated() {
        UcpConfig d = UcpConfig.defaults(AGENT);
        UcpConfig old = new UcpConfig(AGENT, d.connectTimeout(), d.requestTimeout(), d.completeTimeout(), d.maxRetries(),
                d.backoffInitial(), d.backoffCap(), d.breakerThreshold(), d.breakerOpenFor(), d.maxResponseBytes());

        assertThat(d.rateBurst()).isEqualTo(10);
        assertThat(d.ratePerSecond()).isEqualTo(5);
        assertThat(old).isEqualTo(d);
        assertThat(d.withRate(3, 0.5).rateBurst()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> d.withRate(0, 5)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> d.withRate(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 8.4 (UCP-Agent comes from configuration and is required)
    @Test
    void config_rejectsBlankAgent() {
        assertThatThrownBy(() -> UcpConfig.defaults(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 8.3, 7.5 (complete's timeout must not be shorter than the others; the quiet period is sized from it)
    @Test
    void config_rejectsCompleteTimeoutShorterThanRequestTimeout() {
        UcpConfig d = UcpConfig.defaults(AGENT);

        assertThatThrownBy(() -> new UcpConfig(AGENT, d.connectTimeout(), Duration.ofSeconds(5), Duration.ofSeconds(4),
                d.maxRetries(), d.backoffInitial(), d.backoffCap(), d.breakerThreshold(), d.breakerOpenFor(),
                d.maxResponseBytes())).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 8.2 (backoff cap must not be below the first delay; counts and durations must be sane)
    @Test
    void config_rejectsInvalidBackoffRetriesAndBreaker() {
        UcpConfig d = UcpConfig.defaults(AGENT);

        assertThatThrownBy(() -> new UcpConfig(AGENT, d.connectTimeout(), d.requestTimeout(), d.completeTimeout(),
                d.maxRetries(), Duration.ofSeconds(3), Duration.ofSeconds(2), d.breakerThreshold(), d.breakerOpenFor(),
                d.maxResponseBytes())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UcpConfig(AGENT, d.connectTimeout(), d.requestTimeout(), d.completeTimeout(),
                -1, d.backoffInitial(), d.backoffCap(), d.breakerThreshold(), d.breakerOpenFor(),
                d.maxResponseBytes())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UcpConfig(AGENT, d.connectTimeout(), d.requestTimeout(), d.completeTimeout(),
                d.maxRetries(), d.backoffInitial(), d.backoffCap(), 0, d.breakerOpenFor(),
                d.maxResponseBytes())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UcpConfig(AGENT, Duration.ZERO, d.requestTimeout(), d.completeTimeout(),
                d.maxRetries(), d.backoffInitial(), d.backoffCap(), d.breakerThreshold(), d.breakerOpenFor(),
                d.maxResponseBytes())).isInstanceOf(IllegalArgumentException.class);
    }
}
