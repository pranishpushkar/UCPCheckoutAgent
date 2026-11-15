package dev.ucplab.service.config;

import dev.ucplab.ucp.registry.MerchantSettings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.ucp.UcpHttpGateway;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What a merchant's file entry becomes at build time (DESIGN 19, 8.5, 26.2): its instrument, the handler its gateway
 * requires, and the refusal when the two disagree. A token is resolved from the environment by {@code token_ref} and is
 * never in a message (I8).
 */
class MerchantWiringTest {

    private static final MerchantId A = new MerchantId("merchant-a");
    private static final String TOKEN = "tok_planted_canary_9f3a";
    private static final PaymentInstrument LAB_CARD = PaymentInstrument.mockCard("pi_lab", "tok_ok");

    private static MerchantSettings settings(Optional<MerchantSettings.PaymentHandler> handler) {
        return new MerchantSettings(A, "Shop", URI.create("http://127.0.0.1:9101"), MerchantStatus.ACTIVE, Optional.empty(),
                Set.of(), MerchantSettings.DEFAULT_VERSIONS, handler);
    }

    private static final MerchantSettings.PaymentHandler SAMPLE = new MerchantSettings.PaymentHandler(
            "mock_payment_handler", "card", "token", "env:SAMPLE_CARD_TOKEN");

    // Covers: 19, 8.5 (a merchant with no payment_handler pays with the lab's default mock card, and its gateway requires mock_pay_1)
    @Test
    void aMerchantWithNoHandler_usesTheDefaultInstrument_andTheLabHandler() {
        MerchantSettings s = settings(Optional.empty());

        assertThat(MerchantWiring.instrument(s, LAB_CARD, Environment.of(Map.of()))).isSameAs(LAB_CARD);
        assertThat(MerchantWiring.requiredHandler(s)).isEqualTo(UcpHttpGateway.PAYMENT_HANDLER);
        MerchantWiring.requireMatchingInstrument(s, LAB_CARD);
    }

    // Covers: 19, 8.5, I8 (a payment_handler builds the instrument from its shape and the token its token_ref names in the
    // environment; the instrument's own text never shows the token)
    @Test
    void aHandler_buildsItsInstrument_fromTheEnvironmentToken() {
        MerchantSettings s = settings(Optional.of(SAMPLE));

        PaymentInstrument i = MerchantWiring.instrument(s, LAB_CARD, Environment.of(Map.of("SAMPLE_CARD_TOKEN", TOKEN)));

        assertThat(i.handlerId()).isEqualTo("mock_payment_handler");
        assertThat(i.type()).isEqualTo("card");
        assertThat(i.credentialType()).isEqualTo("token");
        assertThat(i.token()).isEqualTo(TOKEN);
        assertThat(i.toString()).doesNotContain(TOKEN);
        assertThat(MerchantWiring.requiredHandler(s)).isEqualTo("mock_payment_handler");
        MerchantWiring.requireMatchingInstrument(s, i);
    }

    // Covers: 19, I8 (a token_ref whose variable is unset refuses to build, naming the merchant and the variable, never a value)
    @Test
    void aTokenRefThatIsUnset_refusesToBuild_namingTheVariableOnly() {
        MerchantSettings s = settings(Optional.of(SAMPLE));

        assertThatThrownBy(() -> MerchantWiring.instrument(s, LAB_CARD, Environment.of(Map.of("OTHER", TOKEN))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the token_ref of merchant merchant-a names SAMPLE_CARD_TOKEN, which is not set")
                .message().doesNotContain(TOKEN);
    }

    // Covers: 8.5 (the handler the gateway requires and the instrument's handler must be equal, or the stack does not build:
    // the default instrument for a merchant whose file names another handler, and the reverse)
    @Test
    void aHandlerThatDisagreesWithTheInstrument_refusesToBuild() {
        assertThatThrownBy(() -> MerchantWiring.requireMatchingInstrument(settings(Optional.of(SAMPLE)), LAB_CARD))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("merchant-a")
                .hasMessageContaining("mock_payment_handler").hasMessageContaining("mock_pay_1")
                .message().doesNotContain("tok_ok");
        PaymentInstrument sample = PaymentInstrument.sampleCard("pi_s", TOKEN);
        assertThatThrownBy(() -> MerchantWiring.requireMatchingInstrument(settings(Optional.empty()), sample))
                .isInstanceOf(IllegalStateException.class).message().doesNotContain(TOKEN);
    }
}
