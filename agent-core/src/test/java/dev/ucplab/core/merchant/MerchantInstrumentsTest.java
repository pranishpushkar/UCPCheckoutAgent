package dev.ucplab.core.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MerchantInstrumentsTest {

    // Covers: 8.5, 19, I8 (the gateway's handler id and the instrument's must be equal; a mismatch names the merchant and
    // both ids and never the token)
    @Test
    void requireMatchingHandler_passesWhenEqual_andRefusesAMismatch_withoutTheToken() {
        MerchantInstruments.requireMatchingHandler(new MerchantId("shop-1"), "mock_pay_1",
                PaymentInstrument.mockCard("pi_1", "tok_secret_canary"));

        assertThatThrownBy(() -> MerchantInstruments.requireMatchingHandler(new MerchantId("sample"),
                "mock_payment_handler", PaymentInstrument.mockCard("pi_1", "tok_secret_canary")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sample").hasMessageContaining("mock_payment_handler").hasMessageContaining("mock_pay_1")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("tok_secret_canary"));
    }
}
