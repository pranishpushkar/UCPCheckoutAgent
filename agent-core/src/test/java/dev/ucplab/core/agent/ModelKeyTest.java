package dev.ucplab.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModelKeyTest {

    // Covers: I8 (the key never shows in toString, string concatenation or a refused key's message)
    @Test
    void modelKey_toString_isRedacted() {
        ModelKey key = ModelKey.of("canary-0123456789abcdef0123456789abcdef");

        assertThat(key.toString()).isEqualTo("ModelKey[redacted]");
        assertThat("key " + key).doesNotContain("canary-");
        assertThat(key.reveal()).isEqualTo("canary-0123456789abcdef0123456789abcdef");
        assertThatThrownBy(() -> ModelKey.of("   ")).hasMessage("a model key must not be blank");
    }

    // Covers: 6.3, 6.5 (a call timeout is positive and never more than the whole 30 s wall-clock budget)
    @Test
    void settings_callTimeout_atMostTheWallClockBudget() {
        ModelClientSettings d = ModelClientSettings.defaults();

        assertThat(d.callTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(d.model()).isEmpty();
        assertThat(d.baseUrl()).isEmpty();
        assertThat(d.withCallTimeout(Duration.ofMillis(1)).callTimeout()).isEqualTo(Duration.ofMillis(1));
        assertThatThrownBy(() -> d.withCallTimeout(Duration.ofSeconds(30).plusMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.withCallTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.withModel(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(d.withModel("m").withBaseUrl(URI.create("http://127.0.0.1:1")).model()).isEqualTo(Optional.of("m"));
    }
}
