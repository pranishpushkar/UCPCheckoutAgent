package dev.ucplab.ucp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** The credential_ref value (26.4, 19, I8; H5 C6): {@code client_id:client_secret}, split at the first colon. */
class ClientCredentialsTest {

    // Covers: 26.4 (split at the first colon: the secret may hold colons, the client id never does)
    @Test
    void parse_splitsAtTheFirstColon() {
        ClientCredentials c = ClientCredentials.parse("client-1:se:cr:et");

        assertThat(c.clientId()).isEqualTo("client-1");
        assertThat(c.clientSecret()).isEqualTo("se:cr:et");
    }

    // Covers: 26.4, 19, I8 (a value that does not split into two non-blank parts is refused with the fixed reason,
    // which never holds the value)
    @ParameterizedTest
    @ValueSource(strings = {"no-colon-canary", ":secret-canary", "client-canary:", "   :secret-canary",
            "client-canary:   ", ""})
    void parse_refusesAMalformedValue_withAFixedReason(String value) {
        assertThatThrownBy(() -> ClientCredentials.parse(value)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ClientCredentials.MALFORMED).message().doesNotContain("canary");
    }

    // Covers: 26.4, I8 (the redactor gets the whole value and, on its own, the secret; the client id alone is not a
    // secret)
    @Test
    void redactable_isTheWholeValue_thenTheSecret() {
        assertThat(ClientCredentials.parse("client-1:secret-1").redactable())
                .isEqualTo(List.of("client-1:secret-1", "secret-1"));
    }
}
