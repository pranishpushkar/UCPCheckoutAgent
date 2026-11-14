package dev.ucplab.persistence;

import dev.ucplab.core.identity.SessionRepository;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.identity.UserSession;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SessionRepository} over {@code user_session} (section 10, V6, 18.5). The cookie value is hashed before any
 * statement sees it, so only {@code id_hash} reaches the database. Expiry is judged in SQL against the injected clock
 * (fixed-width times compare as text, section 10): idle when {@code last_seen_at} is 12 hours or more before now,
 * over when {@code expires_at} is not after now. Never cached (section 20).
 */
public final class SqlSessionRepository implements SessionRepository {

    private static final String COLUMNS = "id_hash, user_id, csrf_token, auth_time, created_at, last_seen_at, expires_at";
    /** Binds the idle cut-off (now - 12 h), then now. */
    private static final String LIVE = "last_seen_at > ? AND expires_at > ?";

    private final Database db;
    private final Clock clock;

    public SqlSessionRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void insert(UserSession session) {
        Objects.requireNonNull(session, "session");
        db.write(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO user_session (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                s.setString(1, session.idHash());
                s.setString(2, session.user().value());
                s.setString(3, session.csrfToken());
                s.setString(4, SqlTimes.format(session.authTime()));
                s.setString(5, SqlTimes.format(session.createdAt()));
                s.setString(6, SqlTimes.format(session.lastSeenAt()));
                s.setString(7, SqlTimes.format(session.expiresAt()));
                s.executeUpdate();
            } catch (SQLException e) {
                if (SqlErrors.isUniqueViolation(e)) {
                    throw new IllegalArgumentException("a session with this id already exists");
                }
                throw e;
            }
            return null;
        });
    }

    @Override
    public Optional<UserSession> findLive(String rawId) {
        String hash = UserSession.hashOf(rawId);
        Instant now = clock.instant();
        return db.read(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM user_session WHERE id_hash = ? AND " + LIVE)) {
                s.setString(1, hash);
                bindLive(s, 2, now);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new UserSession(r.getString("id_hash"), new UserId(r.getString("user_id")),
                            r.getString("csrf_token"), SqlTimes.parse(r.getString("auth_time")),
                            SqlTimes.parse(r.getString("created_at")), SqlTimes.parse(r.getString("last_seen_at")),
                            SqlTimes.parse(r.getString("expires_at"))));
                }
            }
        });
    }

    @Override
    public boolean touch(String rawId) {
        String hash = UserSession.hashOf(rawId);
        Instant now = clock.instant();
        return db.write(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE user_session SET last_seen_at = ? WHERE id_hash = ? AND " + LIVE)) {
                s.setString(1, SqlTimes.format(now));
                s.setString(2, hash);
                bindLive(s, 3, now);
                return s.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean delete(String rawId) {
        String hash = UserSession.hashOf(rawId);
        return db.write(c -> {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM user_session WHERE id_hash = ?")) {
                s.setString(1, hash);
                return s.executeUpdate() == 1;
            }
        });
    }

    @Override
    public int deleteAllFor(UserId user) {
        Objects.requireNonNull(user, "user");
        return db.write(c -> {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM user_session WHERE user_id = ?")) {
                s.setString(1, user.value());
                return s.executeUpdate();
            }
        });
    }

    private static void bindLive(PreparedStatement s, int first, Instant now) throws SQLException {
        s.setString(first, SqlTimes.format(now.minus(UserSession.IDLE_TIMEOUT)));
        s.setString(first + 1, SqlTimes.format(now));
    }
}
