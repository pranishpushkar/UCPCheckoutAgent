package dev.ucplab.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The behaviour every {@link SessionRepository} must have (18.5 [CONTRACT]): sessions are found by the cookie value but
 * stored by its SHA-256 hash, expire after 12 hours idle or 7 days in all, on the injected clock, and can be deleted one
 * at a time or all at once for a user. One suite, run against the in-memory adapter and the SQL adapter.
 */
public abstract class SessionRepositoryContract {

    protected static final Instant T0 = Instant.parse("2026-02-01T08:00:00.000000001Z");
    protected static final UserId ALICE = new UserId("local-alice");
    protected static final UserId BOB = new UserId("local-bob");
    protected static final String RAW = "c2Vzc2lvbi1pZC1mb3ItYWxpY2UtMDEyMzQ1Njc4OWFiY2RlZg";

    protected final MutableClock clock = new MutableClock(T0);
    protected SessionRepository repo;

    /** A fresh, empty repository that reads time from {@code clock}. */
    protected abstract SessionRepository create(Clock clock);

    @BeforeEach
    void createRepository() {
        repo = create(clock);
    }

    protected static UserSession session(String raw, UserId user, Instant at) {
        return UserSession.open(raw, user, "csrf-" + raw, at.minusSeconds(5), at);
    }

    // Covers: 18.5 (a session is found by its cookie value and reads back as written)
    @Test
    void insert_thenFindLive_roundTrips() {
        UserSession s = session(RAW, ALICE, T0);
        repo.insert(s);

        assertThat(repo.findLive(RAW)).contains(s);
        assertThat(s.expiresAt()).isEqualTo(T0.plus(Duration.ofDays(7)));
        assertThat(s.lastSeenAt()).isEqualTo(T0);
    }

    // Covers: 18.5 (only the hash is kept: the stored hash is not itself a cookie, and an unknown cookie finds nothing)
    @Test
    void findLive_byHashOrUnknownValue_isEmpty() {
        UserSession s = session(RAW, ALICE, T0);
        repo.insert(s);

        assertThat(s.idHash()).isEqualTo(UserSession.hashOf(RAW)).isNotEqualTo(RAW).hasSize(64);
        assertThat(repo.findLive(s.idHash())).isEmpty();
        assertThat(repo.findLive(RAW + "x")).isEmpty();
    }

    // Covers: 18.5 (a session id is issued once: a second insert of the same id is refused)
    @Test
    void insert_sameIdTwice_isRefused() {
        repo.insert(session(RAW, ALICE, T0));

        assertThatThrownBy(() -> repo.insert(session(RAW, BOB, T0))).isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.findLive(RAW)).hasValueSatisfying(s -> assertThat(s.user()).isEqualTo(ALICE));
    }

    // Covers: 18.5 (12-hour idle timeout on the injected clock: 1 ns before is live, exactly 12 h idle is expired)
    @Test
    void findLive_idleTimeout_boundary() {
        repo.insert(session(RAW, ALICE, T0));

        clock.advance(Duration.ofHours(12).minusNanos(1));
        assertThat(repo.findLive(RAW)).isPresent();
        clock.advance(Duration.ofNanos(1));
        assertThat(repo.findLive(RAW)).isEmpty();
    }

    // Covers: 18.5 (a touch moves last_seen_at to now, so the idle timeout runs from the last activity)
    @Test
    void touch_extendsTheIdleTimeout() {
        repo.insert(session(RAW, ALICE, T0));
        clock.advance(Duration.ofHours(11));

        assertThat(repo.touch(RAW)).isTrue();
        clock.advance(Duration.ofHours(11));

        assertThat(repo.findLive(RAW)).hasValueSatisfying(s -> assertThat(s.lastSeenAt())
                .isEqualTo(T0.plus(Duration.ofHours(11))));
        clock.advance(Duration.ofHours(1));
        assertThat(repo.findLive(RAW)).isEmpty();
    }

    // Covers: 18.5 (7-day absolute maximum: however recently touched, 1 ns before is live and exactly 7 days is expired)
    @Test
    void findLive_absoluteTimeout_boundary() {
        repo.insert(session(RAW, ALICE, T0));
        Instant end = T0.plus(Duration.ofDays(7));
        while (clock.instant().plus(Duration.ofHours(11)).isBefore(end)) {
            clock.advance(Duration.ofHours(11));
            assertThat(repo.touch(RAW)).isTrue();
        }

        clock.advance(Duration.between(clock.instant(), end.minusNanos(1)));
        assertThat(repo.findLive(RAW)).isPresent();
        clock.advance(Duration.ofNanos(1));
        assertThat(repo.findLive(RAW)).isEmpty();
    }

    // Covers: 18.5 (an expired session cannot be revived by a touch)
    @Test
    void touch_expiredOrMissing_isFalseAndRevivesNothing() {
        repo.insert(session(RAW, ALICE, T0));
        clock.advance(Duration.ofHours(12));

        assertThat(repo.touch(RAW)).isFalse();
        assertThat(repo.findLive(RAW)).isEmpty();
        assertThat(repo.touch("never-issued")).isFalse();
    }

    // Covers: 18.7 (logout deletes the one session; the user's other sessions stay)
    @Test
    void delete_removesOnlyThatSession() {
        repo.insert(session(RAW, ALICE, T0));
        repo.insert(session("second-device", ALICE, T0));

        assertThat(repo.delete(RAW)).isTrue();
        assertThat(repo.delete(RAW)).isFalse();

        assertThat(repo.findLive(RAW)).isEmpty();
        assertThat(repo.findLive("second-device")).isPresent();
    }

    // Covers: 18.7 (delete-all-for-user removes every session of that user and no one else's)
    @Test
    void deleteAllFor_removesTheUsersSessionsOnly() {
        repo.insert(session(RAW, ALICE, T0));
        repo.insert(session("second-device", ALICE, T0));
        repo.insert(session("bobs", BOB, T0));

        assertThat(repo.deleteAllFor(ALICE)).isEqualTo(2);

        assertThat(repo.findLive(RAW)).isEmpty();
        assertThat(repo.findLive("second-device")).isEmpty();
        assertThat(repo.findLive("bobs")).isPresent();
        assertThat(repo.deleteAllFor(ALICE)).isZero();
    }
}
