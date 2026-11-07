package dev.ucplab.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Base32Test {

    // Covers: 7.4 (base32 step of the key). Vectors from RFC 4648 section 10, padding removed.
    @ParameterizedTest
    @CsvSource(value = {
            "'',''",
            "f,MY",
            "fo,MZXQ",
            "foo,MZXW6",
            "foob,MZXW6YQ",
            "fooba,MZXW6YTB",
            "foobar,MZXW6YTBOI"
    })
    void base32_matchesRfc4648WithoutPadding_forTestVectors(String input, String expected) {
        assertThat(Base32.encode(input.getBytes(StandardCharsets.US_ASCII))).isEqualTo(expected);
    }
}
