package dev.ucplab.persistence;

import dev.ucplab.core.identity.AppUser;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.identity.UserRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link UserRepository} over {@code app_user} (section 10, V6). A sign-in is one upsert in one transaction, so two
 * concurrent first sign-ins of the same user give one row. Never cached (section 20).
 */
public final class SqlUserRepository implements UserRepository {

    private final Database db;

    public SqlUserRepository(Database db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    @Override
    public AppUser recordLogin(UserId id, String email, Instant at) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(at, "at");
        return db.write(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO app_user (id, email, created_at, last_login_at) VALUES (?, ?, ?, ?)"
                            + " ON CONFLICT (id) DO UPDATE SET email = excluded.email,"
                            + " last_login_at = excluded.last_login_at")) {
                s.setString(1, id.value());
                s.setString(2, email);
                s.setString(3, SqlTimes.format(at));
                s.setString(4, SqlTimes.format(at));
                s.executeUpdate();
            }
            return find(c, id).orElseThrow();
        });
    }

    @Override
    public Optional<AppUser> find(UserId id) {
        Objects.requireNonNull(id, "id");
        return db.read(c -> find(c, id));
    }

    private static Optional<AppUser> find(Connection c, UserId id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT id, email, created_at, last_login_at FROM app_user WHERE id = ?")) {
            s.setString(1, id.value());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AppUser(new UserId(r.getString("id")), r.getString("email"),
                        SqlTimes.parse(r.getString("created_at")), SqlTimes.parse(r.getString("last_login_at"))));
            }
        }
    }
}
