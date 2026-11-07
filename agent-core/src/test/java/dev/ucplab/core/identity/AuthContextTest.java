package dev.ucplab.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuthContextTest {

    private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration MAX_AGE = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock(T0);
    private final AuthContext auth = new AuthContext(new UserId("alice"), "alice@example.com", T0, "sess-secret");

    // Covers: T4 (a sign-in exactly maxAge old is still recent; one tick later it is not)
    @Test
    void isRecent_isTrueUpToMaxAge_andFalseAfter() {
        clock.advance(MAX_AGE);
        assertThat(auth.isRecent(clock, MAX_AGE)).isTrue();

        clock.advance(Duration.ofMillis(1));
        assertThat(auth.isRecent(clock, MAX_AGE)).isFalse();
    }

    // Covers: I8 (the email and session id do not appear in toString, which may reach logs)
    @Test
    void toString_omitsEmailAndSessionId() {
        assertThat(auth.toString()).doesNotContain("alice@example.com").doesNotContain("sess-secret");
    }
}
