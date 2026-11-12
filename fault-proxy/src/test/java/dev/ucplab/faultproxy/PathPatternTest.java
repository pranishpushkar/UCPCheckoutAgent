package dev.ucplab.faultproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PathPatternTest {

    // Covers: §12 Matching (`*` one segment, `**` any number of segments)
    @ParameterizedTest(name = "{0} vs {1} -> {2}")
    @CsvSource({
            "/ucp/v1/checkout-sessions/*/complete, /ucp/v1/checkout-sessions/cs_1/complete, true",
            "/ucp/v1/checkout-sessions/*/complete, /ucp/v1/checkout-sessions/complete, false",
            "/ucp/v1/checkout-sessions/*/complete, /ucp/v1/checkout-sessions//complete, false",
            "/ucp/v1/checkout-sessions/*/complete, /ucp/v1/checkout-sessions/a/b/complete, false",
            "/ucp/v1/checkout-sessions/*/complete, /ucp/v1/checkout-sessions/cs_1/complete/, false",
            "/ucp/v1/checkout-sessions, /ucp/v1/checkout-sessions, true",
            "/ucp/v1/checkout-sessions, /ucp/v1/checkout-sessions/cs_1, false",
            "/ucp/v1/checkout-sessions, /ucp/v1/Checkout-Sessions, false",
            "/ucp/v1/checkout-sessions/*, /ucp/v1/checkout-sessions/cs_1, true",
            "/ucp/v1/checkout-sessions/*, /ucp/v1/checkout-sessions, false",
            "/ucp/v1/**, /ucp/v1, true",
            "/ucp/v1/**, /ucp/v1/checkout-sessions, true",
            "/ucp/v1/**, /ucp/v1/checkout-sessions/cs_1/complete, true",
            "/ucp/v1/**, /ucp/v2/checkout-sessions, false",
            "/ucp/v1/**, /.well-known/ucp, false",
            "/**/complete, /ucp/v1/checkout-sessions/cs_1/complete, true",
            "/**/complete, /complete, true",
            "/**/complete, /ucp/v1/checkout-sessions/cs_1, false",
            "/**, /, true",
            "/**, /anything/at/all, true",
            "/, /, true",
            "/, /x, false",
            "/a/**/b/*, /a/x/y/b/z, true",
            "/a/**/b/*, /a/b/z, true",
            "/a/**/b/*, /a/b, false",
            "/ucp/v1/checkout-sessions/*/complete, ucp/v1/checkout-sessions/cs_1/complete, false",
    })
    void matches_followsTheGlobRules(String pattern, String path, boolean expected) {
        assertThat(new PathPattern(pattern).matches(path)).isEqualTo(expected);
    }

    // Covers: §12 Matching (a pattern is a path; `*` is only ever a whole segment)
    @ParameterizedTest
    @ValueSource(strings = {"", "ucp/v1", "*", "/ucp/v1/cs_*", "/a/***", "/a/*b/c"})
    void constructor_refusesAMalformedPattern(String pattern) {
        assertThatThrownBy(() -> new PathPattern(pattern)).isInstanceOf(IllegalArgumentException.class);
    }
}
