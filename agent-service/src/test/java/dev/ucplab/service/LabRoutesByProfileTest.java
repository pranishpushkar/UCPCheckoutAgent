package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.SessionRepository;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.identity.UserRepository;
import dev.ucplab.core.identity.UserSession;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.service.lab.LabController;
import dev.ucplab.service.lab.LabControls;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 23.3, m6-plan section 6: the lab routes exist in {@code local} only. In {@code prod} each is 404 in the error shape,
 * with a session or without one; in {@code local}, 401 without a session and the real behaviour with one.
 */
class LabRoutesByProfileTest {

    private static final String[][] ROUTES = {{"PUT", "/api/lab/faults"}, {"DELETE", "/api/lab/faults"},
            {"GET", "/api/lab/orders?merchant_id=merchant-a"}, {"POST", "/api/lab/reset"}};

    @TempDir
    Path dir;

    private TestService service;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    // Covers: 23.3, 23.5 (prod: the four lab routes are 404 not_found in the error shape, with a session injected into the
    // session store and without one; and no lab bean exists)
    @Test
    void prod_labRoutesAreNotFound_withOrWithoutASession() throws IOException {
        service = TestService.start(dir, Map.of("AGENT_PROFILE", "prod"), Optional.empty());
        ApiClient api = new ApiClient(service.port());
        Clock clock = service.bean(Clock.class);
        service.bean(UserRepository.class).recordLogin(new UserId("prod-user"), "u@example.com", clock.instant());
        UserSession session = UserSession.open("raw-session-id-for-the-test-0123456789", new UserId("prod-user"),
                "csrf-token-for-the-test-0123456789ab", clock.instant(), clock.instant());
        service.bean(SessionRepository.class).insert(session);

        for (String[] r : ROUTES) {
            for (List<String> headers : List.of(List.<String>of(), List.of("Cookie",
                    "ucplab_session=raw-session-id-for-the-test-0123456789", "X-CSRF-Token",
                    "csrf-token-for-the-test-0123456789ab"))) {
                String[] h = new String[headers.size() + 2];
                h[0] = "Content-Type";
                h[1] = "application/json";
                for (int i = 0; i < headers.size(); i++) {
                    h[i + 2] = headers.get(i);
                }
                HttpResponse<String> response = api.send(r[0], r[1], Optional.of("{}"), h);
                assertThat(response.statusCode()).as(r[0] + " " + r[1] + " " + headers.size()).isEqualTo(404);
                Map<String, Object> body = LenientJson.readObject(response.body());
                assertThat(body.keySet()).containsExactlyInAnyOrder("code", "message", "request_id");
                assertThat(body).containsEntry("code", "not_found");
            }
        }
        assertThat(service.context.getBeansOfType(LabControls.class)).isEmpty();
        assertThat(service.context.getBeansOfType(LabController.class)).isEmpty();
    }

    // Covers: 23.3, 18.5 (local: 401 without a session, then the real behaviour with one)
    @Test
    void local_labRoutes_are401ThenReal() throws IOException {
        service = TestService.start(dir);
        ApiClient api = new ApiClient(service.port());

        for (String[] r : ROUTES) {
            assertThat(api.send(r[0], r[1], Optional.of("{}"), "Content-Type", "application/json").statusCode())
                    .as(r[0] + " " + r[1]).isEqualTo(401);
        }
        ApiClient.Session alice = api.signIn("alice");
        assertThat(api.get(alice, "/api/lab/orders?merchant_id=merchant-a").statusCode()).isEqualTo(200);
        assertThat(api.post(alice, "/api/lab/reset", "{}").statusCode()).isEqualTo(204);
        assertThat(service.context.getBeansOfType(LabControls.class)).hasSize(1);
    }
}
