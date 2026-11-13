package dev.ucplab.service;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /healthz} (23.4): 200 while the process is up. {@code /readyz} arrives with the wiring (C9). */
@RestController
class HealthController {

    @GetMapping("/healthz")
    Map<String, String> healthz() {
        return Map.of("status", "up");
    }
}
