package dev.ucplab.ucp.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The catalog source's token kept ahead of its calls (26.7, 20, I8). */
class TokenRefresherTest {

    private static final URI ENDPOINT = URI.create("https://api.shopify.example/auth/access_token");
    private static final ClientCredentials CREDENTIALS = new ClientCredentials("CANARY-client-id-7a2f", "CANARY-secret-1c3d");
    private static final Duration MARGIN = Duration.ofSeconds(60);

    private final TokenProviderTest.TestClock clock = new TokenProviderTest.TestClock();
    private final TokenProviderTest.Endpoint endpoint = new TokenProviderTest.Endpoint();
    private final TokenProvider tokens = new TokenProvider(ENDPOINT, CREDENTIALS, endpoint, clock,
            Duration.ofMinutes(30), MARGIN, Duration.ofSeconds(5));
    private final TokenRefresher refresher = new TokenRefresher(tokens, clock);

    // Covers: 26.7, 20 (the first refresh fetches the token, and the next is due at its refresh time: the margin
    // before it ends)
    @Test
    void refresh_fetchesTheToken_andIsDueAgainAtItsRefreshTime() {
        Duration next = refresher.refresh();

        assertThat(endpoint.calls).hasSize(1);
        assertThat(next).isEqualTo(Duration.ofSeconds(3600).minus(MARGIN));
        assertThat(tokens.current()).isInstanceOf(TokenResult.Ok.class);
        assertThat(endpoint.calls).as("a call after the refresh fetches nothing").hasSize(1);
    }

    // Covers: 26.7, 20 (at its refresh time the token is fetched again, before it ends, so no call waits for one)
    @Test
    void refresh_atTheRefreshTime_fetchesANewToken() {
        Duration next = refresher.refresh();
        clock.advance(next);

        Duration after = refresher.refresh();

        assertThat(endpoint.calls).hasSize(2);
        assertThat(after).isEqualTo(Duration.ofSeconds(3600).minus(MARGIN));
        assertThat(((TokenResult.Ok) tokens.current()).token().authorizationHeader()).isEqualTo("Bearer CANARY-token-2");
    }

    // Covers: 26.7 (a refresh before the refresh time fetches nothing, and waits at least the floor)
    @Test
    void refresh_beforeTheRefreshTime_fetchesNothing() {
        Duration next = refresher.refresh();
        clock.advance(next.minusMillis(200));

        Duration after = refresher.refresh();

        assertThat(endpoint.calls).hasSize(1);
        assertThat(after).isEqualTo(TokenRefresher.FLOOR);
    }

    // Covers: 26.7, I8 (a fetch that fails is tried again 30 s later; the refresher's text names no secret)
    @Test
    void aFailedFetch_isTriedAgainThirtySecondsLater() {
        endpoint.answer = () -> new IOException("refused");

        Duration next = refresher.refresh();

        assertThat(endpoint.calls).hasSize(1);
        assertThat(next).isEqualTo(Duration.ofSeconds(30)).isEqualTo(TokenRefresher.RETRY);
        assertThat(refresher.toString()).doesNotContain("CANARY");
    }
}
