package dev.ucplab.service;

import static dev.ucplab.service.ApiClient.cookieValue;
import static dev.ucplab.service.ApiClient.json;
import static dev.ucplab.service.ApiClient.setCookie;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.AppUser;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.identity.UserSession;
import dev.ucplab.persistence.Database;
import dev.ucplab.persistence.SqlSessionRepository;
import dev.ucplab.persistence.SqlUserRepository;
import dev.ucplab.service.ApiClient.Session;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The local dev sign-in on real 18.5 sessions and CSRF (m6-plan section 4, C10), over HTTP against the running service
 * with a fake clock. Row by row through 18.5, then 18.3's {@code return_to}, login CSRF, T1, logout and {@code /api/me}.
 */
class SignInTest {

    private static final Instant T0 = Instant.parse("2026-11-14T09:00:00Z");

    @TempDir
    Path dir;

    private final FakeClock clock = new FakeClock(T0);
    private TestService service;
    private ApiClient api;

    private void start() throws IOException {
        start(Map.of());
    }

    private void start(Map<String, String> env) throws IOException {
        service = TestService.start(dir, env, Optional.of(clock));
        api = new ApiClient(service.port());
    }

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    private HttpResponse<String> me(String cookie) {
        return api.get("/api/me", "Cookie", "ucplab_session=" + cookie);
    }

    private HttpResponse<String> logout(Session s, Optional<String> csrf, String body) {
        List<String> headers = new ArrayList<>(List.of("Cookie", "ucplab_session=" + s.cookie(),
                "Content-Type", "application/json"));
        csrf.ifPresent(c -> headers.addAll(List.of("X-CSRF-Token", c)));
        return api.send("POST", "/auth/logout", Optional.of(body), headers.toArray(String[]::new));
    }

    private UserSession stored(String cookie) {
        return service.bean(SqlSessionRepository.class).findLive(cookie).orElseThrow();
    }

    // Covers: 18.5, 18.8 (the cookie is ucplab_session, HttpOnly, SameSite=Lax, Path=/, with no Secure in local; the id
    // is 32 random bytes in base64url)
    @Test
    void cookie_hasTheLocalProfileAttributes() throws IOException {
        start();

        HttpResponse<String> login = api.devLogin("alice", Optional.empty(), Optional.empty());

        assertThat(login.statusCode()).isEqualTo(303);
        String cookie = setCookie(login).orElseThrow();
        List<String> attributes = Stream.of(cookie.split(";")).map(String::trim).toList();
        assertThat(attributes).contains("HttpOnly", "SameSite=Lax", "Path=/");
        assertThat(attributes).noneMatch(a -> a.equalsIgnoreCase("Secure"));
        assertThat(cookie).startsWith("ucplab_session=").doesNotStartWith("__Host-");
        assertThat(cookieValue(login).orElseThrow()).matches("[A-Za-z0-9_-]{43}");
    }

    // Covers: 18.5 (only the SHA-256 hash of the session id is stored: the row holds the hash, and no database file
    // holds the raw value)
    @Test
    void storage_holdsOnlyTheHash() throws IOException {
        start();
        String cookie = api.signIn("alice").cookie();

        List<String> hashes = service.bean(Database.class).read(c -> {
            List<String> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT id_hash FROM user_session")) {
                while (r.next()) {
                    out.add(r.getString(1));
                }
            }
            return out;
        });
        service.close();
        service = null;
        StringBuilder bytes = new StringBuilder();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                bytes.append(new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1));
            }
        }

        assertThat(hashes).containsExactly(UserSession.hashOf(cookie));
        assertThat(bytes.toString()).contains(UserSession.hashOf(cookie)).doesNotContain(cookie);
    }

    // Covers: 18.5 (12-hour idle timeout, on the fake clock: 11 h 59 min idle is still signed in, 12 h is 401)
    @Test
    void expiry_idle() throws IOException {
        start();
        String a = api.signIn("alice").cookie();
        String b = api.signIn("bob").cookie();

        clock.advance(Duration.ofHours(12).minusMinutes(1));
        assertThat(me(a).statusCode()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(1));

        assertThat(me(b).statusCode()).as("12 h idle").isEqualTo(401);
        assertThat(me(a).statusCode()).as("seen a minute ago").isEqualTo(200);
    }

    // Covers: 18.5 (7-day absolute lifetime: kept active every 11 hours, the session still ends 7 days after sign-in)
    @Test
    void expiry_absolute() throws IOException {
        start();
        String a = api.signIn("alice").cookie();

        Duration elapsed = Duration.ZERO;
        while (elapsed.plusHours(11).compareTo(UserSession.ABSOLUTE_LIFETIME) < 0) {
            clock.advance(Duration.ofHours(11));
            elapsed = elapsed.plusHours(11);
            assertThat(me(a).statusCode()).as("at %s", elapsed).isEqualTo(200);
        }
        clock.advance(UserSession.ABSOLUTE_LIFETIME.minus(elapsed));

        assertThat(me(a).statusCode()).as("7 days after sign-in").isEqualTo(401);
    }

    // Covers: 18.5 (last_seen_at is refreshed at most once a minute: not at 59 s, at 60 s)
    @Test
    void touch_atMostOnceAMinute() throws IOException {
        start();
        String a = api.signIn("alice").cookie();

        clock.advance(Duration.ofSeconds(59));
        me(a);
        Instant after59 = stored(a).lastSeenAt();
        clock.advance(Duration.ofSeconds(1));
        me(a);
        Instant after60 = stored(a).lastSeenAt();

        assertThat(after59).isEqualTo(T0);
        assertThat(after60).isEqualTo(T0.plusSeconds(60));
    }

    // Covers: 18.5 (a new session id at every sign-in, including a second sign-in in the same browser; the old session
    // no longer works, which prevents session fixation)
    @Test
    void signIn_issuesANewIdAndEndsTheOldSession() throws IOException {
        start();
        Session first = api.signIn("alice");

        Session second = api.signIn("alice", Optional.of(first.cookie()));

        assertThat(second.cookie()).isNotEqualTo(first.cookie());
        assertThat(me(first.cookie()).statusCode()).isEqualTo(401);
        assertThat(me(second.cookie()).statusCode()).isEqualTo(200);
    }

    // Covers: 18.5 (the CSRF token is random on its own: it is not derived from the session id nor the other way round,
    // and two sign-ins give unrelated pairs)
    @Test
    void csrfToken_isIndependentOfTheSessionId() throws IOException {
        start();
        Session one = api.signIn("alice");
        Session two = api.signIn("alice");

        for (Session s : List.of(one, two)) {
            assertThat(s.csrf()).matches("[A-Za-z0-9_-]{43}");
            assertThat(s.csrf()).doesNotContain(s.cookie());
            assertThat(s.cookie()).doesNotContain(s.csrf());
            assertThat(s.csrf()).isNotEqualTo(UserSession.hashOf(s.cookie()));
        }
        assertThat(List.of(one.cookie(), one.csrf(), two.cookie(), two.csrf())).doesNotHaveDuplicates();
    }

    // Covers: 18.3 (return_to keeps only a relative path starting with / and not //; everything else falls back to /)
    @Test
    void returnTo_onlyARelativePathIsKept() throws IOException {
        start();
        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("/intents/abc?tab=events", "/intents/abc?tab=events");
        cases.put("/", "/");
        cases.put("https://evil.example", "/");
        cases.put("//evil.example", "/");
        cases.put("/\\\\evil.example", "/");
        cases.put("%2F%2Fevil.example", "/");
        cases.put("/%2F/evil.example", "/");
        cases.put("/%5Cevil.example", "/");
        cases.put("javascript:alert(1)", "/");
        cases.put("", "/");

        for (Map.Entry<String, String> c : cases.entrySet()) {
            HttpResponse<String> login = api.devLogin("alice", Optional.of(c.getKey()), Optional.empty());

            assertThat(login.headers().firstValue("Location")).as(c.getKey()).contains(c.getValue());
        }
        HttpResponse<String> none = api.devLogin("alice", Optional.empty(), Optional.empty());
        assertThat(none.headers().firstValue("Location")).contains("/");
    }

    // Covers: 18.3 (the same cleaning on the form path the login page posts, where the servlet has already decoded
    // %2F%2F to //)
    @Test
    void returnTo_formPost_encodedSlashesFallBack() throws IOException {
        start();

        HttpResponse<String> login = api.send("POST", "/auth/dev-login",
                Optional.of("user=alice&return_to=%2F%2Fevil.example"), "Origin", ApiClient.ORIGIN,
                "Content-Type", "application/x-www-form-urlencoded");

        assertThat(login.statusCode()).isEqualTo(303);
        assertThat(login.headers().firstValue("Location")).contains("/");
    }

    // Covers: m6-plan section 4 (login CSRF: a missing or foreign Origin on POST /auth/dev-login is 403 and opens no
    // session)
    @Test
    void devLogin_missingOrForeignOrigin_is403() throws IOException {
        start();
        String body = "{\"user\":\"alice\"}";

        HttpResponse<String> missing = api.send("POST", "/auth/dev-login", Optional.of(body),
                "Content-Type", "application/json");
        HttpResponse<String> foreign = api.send("POST", "/auth/dev-login", Optional.of(body),
                "Origin", "https://evil.example", "Content-Type", "application/json");

        for (HttpResponse<String> r : List.of(missing, foreign)) {
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(json(r)).containsEntry("code", "csrf_failed");
            assertThat(setCookie(r)).isEmpty();
        }
    }

    // Covers: m6-plan section 4, Stage 3 review point 2 (in local both loopback origins sign in; another origin is 403)
    @Test
    void devLogin_bothLoopbackOrigins_areAccepted() throws IOException {
        start();

        for (String origin : List.of("http://localhost:8080", "http://127.0.0.1:8080")) {
            HttpResponse<String> r = api.send("POST", "/auth/dev-login", Optional.of("{\"user\":\"alice\"}"),
                    "Origin", origin, "Content-Type", "application/json");
            assertThat(r.statusCode()).as(origin).isEqualTo(303);
        }
        HttpResponse<String> other = api.send("POST", "/auth/dev-login", Optional.of("{\"user\":\"alice\"}"),
                "Origin", "http://localhost:9090", "Content-Type", "application/json");
        assertThat(other.statusCode()).isEqualTo(403);
    }

    // Covers: 18.8, T1 (an unknown local user is 422 and the body names no user id; LOCAL_USERS decides who exists)
    @Test
    void devLogin_unknownUser_is422WithoutAnId() throws IOException {
        start(Map.of("LOCAL_USERS", "alice"));

        HttpResponse<String> bob = api.devLogin("bob", Optional.empty(), Optional.empty());
        HttpResponse<String> injected = api.devLogin("local-alice", Optional.empty(), Optional.empty());

        for (HttpResponse<String> r : List.of(bob, injected)) {
            assertThat(r.statusCode()).isEqualTo(422);
            assertThat(json(r)).containsEntry("code", "validation_failed");
            assertThat(r.body()).doesNotContain("local-").doesNotContain("bob");
            assertThat(setCookie(r)).isEmpty();
        }
    }

    // Covers: point 5 of the Stage 2 review, 18.2 step 7 (the dev sign-in upserts app_user: created once, then
    // last_login_at moves)
    @Test
    void devLogin_upsertsAppUser() throws IOException {
        start();
        api.signIn("alice");
        clock.advance(Duration.ofMinutes(5));
        api.signIn("alice");

        AppUser alice = service.bean(SqlUserRepository.class).find(new UserId("local-alice")).orElseThrow();

        assertThat(alice.email()).isEqualTo("alice@local.ucplab.dev");
        assertThat(alice.createdAt()).isEqualTo(T0);
        assertThat(alice.lastLoginAt()).isEqualTo(T0.plusSeconds(300));
    }

    // Covers: 23.5, 18.5 (no session is 401 unauthenticated; a POST without the CSRF header or with the wrong token is
    // 403 csrf_failed and does nothing)
    @Test
    void session_andCsrf_areRequired() throws IOException {
        start();
        Session alice = api.signIn("alice");

        HttpResponse<String> anonymous = api.get("/api/me");
        HttpResponse<String> garbage = me("not-a-session");
        HttpResponse<String> anonymousPost = api.send("POST", "/auth/logout", Optional.of("{}"),
                "Content-Type", "application/json");
        HttpResponse<String> noHeader = logout(alice, Optional.empty(), "{}");
        HttpResponse<String> wrong = logout(alice, Optional.of(alice.csrf() + "x"), "{}");
        HttpResponse<String> otherSessions = logout(alice, Optional.of(api.signIn("alice").csrf()), "{}");

        for (HttpResponse<String> r : List.of(anonymous, garbage, anonymousPost)) {
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(json(r)).containsEntry("code", "unauthenticated");
        }
        for (HttpResponse<String> r : List.of(noHeader, wrong, otherSessions)) {
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(json(r)).containsEntry("code", "csrf_failed");
        }
        assertThat(me(alice.cookie()).statusCode()).as("the refused logouts changed nothing").isEqualTo(200);
    }

    // Covers: T1 (a user_id in a request body is ignored: bob's id in alice's logout ends alice's session, not bob's)
    @Test
    void userIdInBody_isIgnored() throws IOException {
        start();
        Session alice = api.signIn("alice");
        Session bob = api.signIn("bob");

        HttpResponse<String> out = logout(alice, Optional.of(alice.csrf()),
                "{\"user_id\":\"local-bob\",\"user\":\"bob\"}");

        assertThat(out.statusCode()).isEqualTo(204);
        assertThat(me(alice.cookie()).statusCode()).isEqualTo(401);
        assertThat(json(me(bob.cookie()))).containsEntry("user_id", "local-bob");
    }

    // Covers: 18.3, 18.7 (logout deletes the session and clears the cookie; the old cookie is then 401; the user's other
    // sessions stay valid)
    @Test
    void logout_deletesTheSessionAndClearsTheCookie() throws IOException {
        start();
        Session laptop = api.signIn("alice");
        Session phone = api.signIn("alice");

        HttpResponse<String> out = logout(laptop, Optional.of(laptop.csrf()), "");

        assertThat(out.statusCode()).isEqualTo(204);
        String cleared = setCookie(out).orElseThrow();
        assertThat(cleared).startsWith("ucplab_session=;").contains("Max-Age=0");
        assertThat(me(laptop.cookie()).statusCode()).isEqualTo(401);
        assertThat(service.bean(SqlSessionRepository.class).findLive(laptop.cookie())).isEmpty();
        assertThat(me(phone.cookie()).statusCode()).isEqualTo(200);
    }

    // Covers: 18.3, 18.5, 18.6 (/api/me gives the user and the reauth flag; the CSRF token is in the X-CSRF-Token header
    // and in no body of the whole flow)
    @Test
    void me_returnsTheUserAndTheTokenOnlyInAHeader() throws IOException {
        start();
        api.get("/auth/login?return_to=/intents/x&reauth=true");
        Session alice = api.signIn("alice");

        HttpResponse<String> fresh = me(alice.cookie());
        clock.advance(Duration.ofMinutes(16));
        HttpResponse<String> stale = me(alice.cookie());
        logout(alice, Optional.of(alice.csrf()), "{}");

        assertThat(json(fresh)).containsEntry("user_id", "local-alice").containsEntry("email", "alice@local.ucplab.dev")
                .containsEntry("auth_time", T0.toString()).containsEntry("approval_reauth_needed", false);
        assertThat(json(stale)).containsEntry("approval_reauth_needed", true);
        assertThat(fresh.headers().firstValue("X-CSRF-Token")).contains(alice.csrf());
        assertThat(api.seen).isNotEmpty().allSatisfy(r -> assertThat(r.body()).doesNotContain(alice.csrf()));
    }

    // Covers: section 20, m6-plan section 11 (every /api and /auth response, errors included, carries no-store)
    @Test
    void apiAndAuthResponses_areNoStore() throws IOException {
        start();
        Session alice = api.signIn("alice");
        api.get("/api/me");
        api.get("/api/nope");
        me(alice.cookie());
        api.get("/auth/login");

        assertThat(api.seen).hasSizeGreaterThan(4).allSatisfy(r ->
                assertThat(r.headers().firstValue("Cache-Control")).as(r.uri().getPath()).contains("no-store"));
    }

    // Covers: 18.8, m6-plan section 4 (prod has no sign-in: the dev routes are 404 and every /api route is 401)
    @Test
    void prod_hasNoSignIn() throws IOException {
        start(Map.of("AGENT_PROFILE", "prod"));

        assertThat(api.get("/auth/login").statusCode()).isEqualTo(404);
        assertThat(api.devLogin("alice", Optional.empty(), Optional.empty()).statusCode()).isEqualTo(404);
        assertThat(api.get("/api/me").statusCode()).isEqualTo(401);
    }

    // Covers: 18.3, 18.6 (the login page lists the local users and carries the cleaned return_to, escaped)
    @Test
    void loginPage_listsTheUsers() throws IOException {
        start();

        HttpResponse<String> page = api.get("/auth/login?return_to=//evil.example&reauth=true");
        HttpResponse<String> kept = api.get("/auth/login?return_to=/intents/abc");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t)
                .startsWith("text/html"));
        assertThat(page.body()).contains("value=\"alice\"", "value=\"bob\"", "name=\"return_to\" value=\"/\"",
                "Sign in again").doesNotContain("evil.example");
        assertThat(kept.body()).contains("name=\"return_to\" value=\"/intents/abc\"");
    }
}
