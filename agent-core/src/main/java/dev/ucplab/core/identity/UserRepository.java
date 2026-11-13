package dev.ucplab.core.identity;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage port for {@code app_user} (section 10, 18.2 step 7: "upsert app_user"). Every method takes the user's own id,
 * which comes from sign-in, never from a request body (T1). Never cached (section 20).
 */
public interface UserRepository {

    /**
     * Records a sign-in: creates the user on their first, and otherwise keeps {@code created_at} and updates the email
     * and {@code last_login_at}.
     *
     * @return the user as stored after the sign-in
     */
    AppUser recordLogin(UserId id, String email, Instant at);

    Optional<AppUser> find(UserId id);
}
