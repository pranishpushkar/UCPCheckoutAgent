package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.merchant.PaymentInstrument;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The handler wiring rule of 8.5 in the service: the lab gateway requires mock_pay_1, so the instrument must be for it. */
class HandlerWiringTest {

    @TempDir
    Path dir;

    // Covers: 8.5, 19 (a service context whose instrument is for another merchant's handler does not start; the lab
    // instrument starts, as every other service test shows)
    @Test
    void anInstrumentForAnotherHandler_refusesToBuildTheStack() {
        assertThatThrownBy(() -> TestService.start(dir, Map.of(), Optional.empty(), List.of(), Optional.empty(),
                Map.of("testPayment", PaymentInstrument.sampleCard("pi_sample", "tok_x"))).close())
                .hasStackTraceContaining("payment handler mismatch").hasStackTraceContaining("mock_payment_handler");
    }
}
