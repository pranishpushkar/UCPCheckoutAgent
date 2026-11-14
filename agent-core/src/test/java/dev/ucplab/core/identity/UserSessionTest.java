package dev.ucplab.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** UserSession (18.5): what it holds, and what it never prints. */
class UserSessionTest {

    private static final Instant T0 = Instant.parse("2026-02-01T08:00:00Z");

    // Covers: 18.5, I8 (toString shows no raw id, id hash or CSRF token)
    @Test
    void toString_holdsNoSecret() {
        String raw = "raw-session-id-canary-0123456789";
        UserSession s = UserSession.open(raw, new UserId("local-alice"), "csrf-canary-token", T0, T0);

        assertThat(s.toString()).doesNotContain(raw).doesNotContain(s.idHash()).doesNotContain("csrf-canary-token")
                .contains("local-alice");
    }

    // Covers: 18.5 (opening sets the lifetimes from the injected instant: 7 days absolute, last seen now)
    @Test
    void open_setsLifetimesFromNow() {
        UserSession s = UserSession.open("raw", new UserId("u"), "csrf", T0.minusSeconds(30), T0);

        assertThat(s.createdAt()).isEqualTo(T0);
        assertThat(s.lastSeenAt()).isEqualTo(T0);
        assertThat(s.expiresAt()).isEqualTo(T0.plus(UserSession.ABSOLUTE_LIFETIME));
        assertThat(s.authTime()).isEqualTo(T0.minusSeconds(30));
        assertThat(UserSession.IDLE_TIMEOUT).isEqualTo(Duration.ofHours(12));
        assertThat(UserSession.ABSOLUTE_LIFETIME).isEqualTo(Duration.ofDays(7));
    }

    // Covers: 18.5 (a blank cookie value or CSRF token is refused)
    @Test
    void open_blankValues_areRefused() {
        assertThatThrownBy(() -> UserSession.open(" ", new UserId("u"), "csrf", T0, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserSession.open("raw", new UserId("u"), "", T0, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
