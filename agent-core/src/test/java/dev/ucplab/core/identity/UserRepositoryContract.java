package dev.ucplab.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The behaviour every {@link UserRepository} must have (section 10 {@code app_user}, 18.2 step 7). */
public abstract class UserRepositoryContract {

    private static final Instant T0 = Instant.parse("2026-11-14T09:00:00.123456789Z");
    private static final UserId ALICE = new UserId("local-alice");

    protected UserRepository repo;

    protected abstract UserRepository create();

    @BeforeEach
    void createRepository() {
        repo = create();
    }

    // Covers: section 10 app_user, 18.2 (the first sign-in creates the user)
    @Test
    void recordLogin_firstSignIn_createsTheUser() {
        AppUser stored = repo.recordLogin(ALICE, "alice@local.ucplab.dev", T0);

        assertThat(stored).isEqualTo(new AppUser(ALICE, "alice@local.ucplab.dev", T0, T0));
        assertThat(repo.find(ALICE)).contains(stored);
    }

    // Covers: section 10 app_user, 18.2 (a later sign-in keeps created_at and updates the email and last_login_at)
    @Test
    void recordLogin_laterSignIn_updatesInPlace() {
        repo.recordLogin(ALICE, "alice@local.ucplab.dev", T0);

        AppUser again = repo.recordLogin(ALICE, "alice@new.ucplab.dev", T0.plusSeconds(60));

        assertThat(again).isEqualTo(new AppUser(ALICE, "alice@new.ucplab.dev", T0, T0.plusSeconds(60)));
        assertThat(repo.find(ALICE)).contains(again);
    }

    // Covers: T6 (an unknown user reads as empty; another user's sign-in does not touch this one)
    @Test
    void find_unknownUser_isEmpty() {
        repo.recordLogin(new UserId("local-bob"), "bob@local.ucplab.dev", T0);

        assertThat(repo.find(ALICE)).isEmpty();
    }
}
