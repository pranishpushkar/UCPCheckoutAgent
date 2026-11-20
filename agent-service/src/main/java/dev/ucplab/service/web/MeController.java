package dev.ucplab.service.web;

import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.intent.BuyerCountry;
import dev.ucplab.service.config.ServiceProperties;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/me} (18.3 [CONTRACT]): who is signed in, whether an approval would need a fresh sign-in (18.6), and the
 * service's model by name only ({@code agent_model}, 23, 24.3), for the console's model label, and the operator's
 * default buyer country ({@code buyer_country}, 23.1), the console's first Deliver to choice.
 * The CSRF token travels only in the {@code X-CSRF-Token} response header (18.5), never in a body.
 */
@RestController
final class MeController {

    private final Clock clock;
    private final String agentModel;
    private final Map<String, Object> buyerCountry;

    MeController(Clock clock, ServiceProperties properties) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.agentModel = properties.agentModel().name().toLowerCase(Locale.ROOT);
        BuyerCountry country = properties.buyerCountry();
        this.buyerCountry = Map.of("country", country.code(), "currency", country.currency(), "name", country.name());
    }

    @GetMapping("/api/me")
    ResponseEntity<Map<String, Object>> me(SignedIn caller) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", caller.auth().user().value());
        body.put("email", caller.auth().email());
        body.put("auth_time", caller.auth().authTime().toString());
        body.put("approval_reauth_needed",
                !caller.auth().isRecent(clock, DefaultApprovalService.DEFAULT_MAX_AUTH_AGE));
        body.put("agent_model", agentModel);
        body.put("buyer_country", buyerCountry);
        return ResponseEntity.ok().header(CsrfInterceptor.HEADER, caller.session().csrfToken()).body(body);
    }
}
