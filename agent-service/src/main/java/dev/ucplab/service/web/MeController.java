package dev.ucplab.service.web;

import dev.ucplab.core.approval.DefaultApprovalService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/me} (18.3 [CONTRACT]): who is signed in, and whether an approval would need a fresh sign-in (18.6).
 * The CSRF token travels only in the {@code X-CSRF-Token} response header (18.5), never in a body.
 */
@RestController
final class MeController {

    private final Clock clock;

    MeController(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping("/api/me")
    ResponseEntity<Map<String, Object>> me(SignedIn caller) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", caller.auth().user().value());
        body.put("email", caller.auth().email());
        body.put("auth_time", caller.auth().authTime().toString());
        body.put("approval_reauth_needed",
                !caller.auth().isRecent(clock, DefaultApprovalService.DEFAULT_MAX_AUTH_AGE));
        return ResponseEntity.ok().header(CsrfInterceptor.HEADER, caller.session().csrfToken()).body(body);
    }
}
