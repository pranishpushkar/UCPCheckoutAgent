package dev.ucplab.service.web;

import dev.ucplab.core.intent.BuyerCountry;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/countries} (23.1): every country with one currency, {@code {country, name, currency}}, sorted by name,
 * the same for every user and built once at startup; the console's Deliver to choices (24.3). A session is needed, as
 * for every {@code /api} path.
 */
@RestController
final class CountriesController {

    private static final Map<String, Object> COUNTRIES = Map.of("countries", BuyerCountry.all().stream()
            .map(c -> Map.of("country", c.code(), "name", c.name(), "currency", c.currency())).toList());

    @GetMapping("/api/countries")
    Map<String, Object> countries(SignedIn caller) {
        return COUNTRIES;
    }
}
