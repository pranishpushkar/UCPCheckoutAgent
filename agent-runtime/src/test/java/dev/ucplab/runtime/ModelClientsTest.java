package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.agent.ModelKey;
import org.junit.jupiter.api.Test;

class ModelClientsTest {

    // Covers: R4, 15 (without agent-adk on the classpath there is no live provider, and the refusal is a fixed
    // message that never holds the key)
    @Test
    void load_noProviderOnTheClasspath_refusesWithAFixedMessage() {
        ModelKey key = ModelKey.of("canary-0123456789abcdef0123456789abcdef");

        assertThat(ModelClients.available("gemini")).isFalse();
        // Not "gemini" here: NoLiveInVerifyTest (C16) keeps that lookup out of every test outside the live profiles.
        assertThatThrownBy(() -> ModelClients.load("absent", key))
                .isInstanceOf(ModelClients.Unavailable.class)
                .hasMessage("no model client provider \"absent\" on the classpath")
                .hasNoCause();
    }
}
