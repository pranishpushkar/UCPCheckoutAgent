package dev.ucplab.core.id;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SequentialIdGeneratorTest {

    // Covers: 7.8 (ids come from an injected generator; the test one is deterministic)
    @Test
    void next_returnsPrefixedSequence() {
        IdGenerator ids = new SequentialIdGenerator("t");

        assertThat(ids.next()).isEqualTo("t-1");
        assertThat(ids.next()).isEqualTo("t-2");
    }
}
