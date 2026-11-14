package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.merchant.FulfillmentPolicy;
import dev.ucplab.core.merchant.PaymentInstrument;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The handler wiring rule of 8.5 in the harness's stack (H1 C6d). */
class HandlerWiringTest {

    private static final TimeScale SCALE = TimeScale.of(4);

    @TempDir
    Path dir;

    // Covers: 8.5, 19, I8 (a stack whose gateway requires the sample's handler but whose instrument is the lab card does
    // not build, and the message has no token; the lab stack and a matching sample pair build)
    @Test
    void aMismatchedPair_refusesToBuild_andMatchingOnesBuild() {
        MerchantWiring mismatched = new MerchantWiring("mock_payment_handler", Set.of("2026-04-08"),
                FulfillmentPolicy.cheapest(), token -> PaymentInstrument.mockCard("pi_x", token));
        MerchantWiring sample = new MerchantWiring("mock_payment_handler", Set.of("2026-04-08"),
                FulfillmentPolicy.cheapest(), token -> PaymentInstrument.sampleCard("pi_x", token));

        assertThatThrownBy(() -> new AgentStack(URI.create("http://127.0.0.1:1"), dir.resolve("a.db"), SCALE, 1,
                new SequentialIdGenerator("a"), "tok_canary_zz", Duration.ofSeconds(1), "", mismatched))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mismatch")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("tok_canary_zz"));
        new AgentStack(URI.create("http://127.0.0.1:1"), dir.resolve("b.db"), SCALE, 1, new SequentialIdGenerator("b"),
                "tok_x", Duration.ofSeconds(1), "", sample).close();
        new AgentStack(URI.create("http://127.0.0.1:1"), dir.resolve("c.db"), SCALE, 1, new SequentialIdGenerator("c"),
                "tok_x").close();
    }
}
