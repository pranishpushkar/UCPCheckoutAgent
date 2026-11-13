package dev.ucplab.service.web;

import dev.ucplab.core.identity.SessionRepository;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.identity.UserRepository;
import dev.ucplab.core.identity.UserSession;
import dev.ucplab.service.config.ServiceProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The local profile's dev sign-in (18.8, m6-plan section 4), until M6b's OIDC flow replaces it. The bean exists only
 * under {@code local}; in {@code prod} these routes are 404 and every {@code /api} route is 401.
 *
 * <p>{@code POST /auth/dev-login} takes {@code {user}} as JSON or as a form. It checks {@code Origin} against
 * {@code PUBLIC_ORIGIN} first (login CSRF: there is no session yet to carry a token), refuses a name not in
 * {@code LOCAL_USERS} with 422 and no id in the body, upserts {@code app_user}, deletes the session the request already
 * carried (a new id at every sign-in, 18.5), and opens a new one: 32 random bytes for the cookie, stored only as their
 * hash, and 32 more, drawn separately, for the CSRF token. Then it redirects to the cleaned {@code return_to}.
 */
@RestController
@Profile("local")
final class DevSignInController {

    static final String UNKNOWN_USER = "unknown local user";
    private static final int TOKEN_BYTES = 32;
    private static final String EMAIL_DOMAIN = "@local.ucplab.dev";

    private final ServiceProperties properties;
    private final SessionRepository sessions;
    private final UserRepository users;
    private final Clock clock;
    private final RandomGenerator random;

    DevSignInController(ServiceProperties properties, SessionRepository sessions, UserRepository users, Clock clock,
                        RandomGenerator random) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.users = Objects.requireNonNull(users, "users");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** {@code GET /auth/login?return_to=&reauth=}: a plain page with one button per local user. */
    @GetMapping(value = "/auth/login", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page(@RequestParam(name = "return_to", required = false) Optional<String> returnTo,
                                @RequestParam(name = "reauth", required = false) Optional<String> reauth) {
        String target = HtmlUtils.htmlEscape(ReturnTo.clean(returnTo));
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<title>Sign in</title></head><body><h1>Sign in (local)</h1>");
        if (reauth.filter("true"::equals).isPresent()) {
            html.append("<p>Sign in again to approve.</p>");
        }
        html.append("<form method=\"post\" action=\"/auth/dev-login\">")
                .append("<input type=\"hidden\" name=\"return_to\" value=\"").append(target).append("\">");
        for (String name : properties.localUsers()) {
            String n = HtmlUtils.htmlEscape(name);
            html.append("<button type=\"submit\" name=\"user\" value=\"").append(n).append("\">").append(n)
                    .append("</button> ");
        }
        html.append("</form></body></html>");
        return ResponseEntity.ok()
                .header("Content-Security-Policy", "default-src 'none'; form-action 'self'; frame-ancestors 'none'")
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(html.toString());
    }

    @PostMapping(value = "/auth/dev-login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> signInJson(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return signIn(Optional.ofNullable(body.get("user")), Optional.ofNullable(body.get("return_to")), request);
    }

    @PostMapping(value = "/auth/dev-login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> signInForm(@RequestParam(name = "user", required = false) Optional<String> user,
                                    @RequestParam(name = "return_to", required = false) Optional<String> returnTo,
                                    HttpServletRequest request) {
        return signIn(user.map(Object.class::cast), returnTo.map(Object.class::cast), request);
    }

    private ResponseEntity<Void> signIn(Optional<Object> user, Optional<Object> returnTo, HttpServletRequest request) {
        if (!properties.publicOrigin().equals(request.getHeader(HttpHeaders.ORIGIN))) {
            throw ApiException.of(ErrorCode.CSRF_FAILED);
        }
        String name = user.filter(String.class::isInstance).map(String.class::cast)
                .filter(properties.localUsers()::contains)
                .orElseThrow(() -> ApiException.of(ErrorCode.VALIDATION_FAILED, UNKNOWN_USER));
        String target = ReturnTo.clean(returnTo.filter(String.class::isInstance).map(String.class::cast));

        SessionCookie.read(request).ifPresent(sessions::delete);
        Instant now = clock.instant();
        UserId id = new UserId("local-" + name);
        users.recordLogin(id, name + EMAIL_DOMAIN, now);
        String rawId = token();
        String csrf = token();
        sessions.insert(UserSession.open(rawId, id, csrf, now, now));

        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(target))
                .header(HttpHeaders.SET_COOKIE, SessionCookie.issue(rawId).toString()).build();
    }

    private String token() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
