package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRepository;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.BuyerCountry;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.service.ApiClient.Session;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run's buyer country on the API (23.1, 18): the operator's default on {@code /api/me}, the choices on
 * {@code /api/countries}, and {@code ship_to_country} on {@code POST /api/runs}, read from the body only, checked
 * against the budget's currency, fixed for the run and copied onto its intents.
 */
class BuyerCountryApiTest {

    @TempDir
    Path dir;

    private TestService service;
    private ApiClient api;

    private void start(Map<String, String> env) throws IOException {
        service = TestService.start(dir, env, Optional.empty(), List.of(), Optional.empty(), Map.of());
        api = new ApiClient(service.port());
    }

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    private Optional<BuyerCountry> runCountry(String user, String runId) {
        return service.bean(RunRepository.class).find(new UserId("local-" + user), new RunId(runId))
                .map(r -> r.shipTo());
    }

    private static String runId(HttpResponse<String> created) {
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return (String) LenientJson.readObject(created.body()).get("run_id");
    }

    // Covers: 18, 23.1, 24.3 (GET /api/me names the operator's default buyer country, its currency and its English name:
    // US when BUYER_COUNTRY is unset)
    @Test
    void me_namesTheOperatorsDefault_USWhenUnset() throws IOException {
        start(Map.of());
        Session alice = api.signIn("alice");

        Map<String, Object> me = LenientJson.readObject(api.get(alice, "/api/me").body());

        assertThat(me).containsEntry("buyer_country",
                Map.of("country", "US", "currency", "USD", "name", "United States"));
    }

    // Covers: 18, 23.1, 24.3 (with BUYER_COUNTRY=IN, /api/me names India; /api/countries lists every country with one
    // currency, sorted by name, the same for every user, and needs a session)
    @Test
    void me_andCountries_followBuyerCountry() throws IOException {
        start(Map.of("BUYER_COUNTRY", "IN"));
        Session alice = api.signIn("alice");

        Map<String, Object> me = LenientJson.readObject(api.get(alice, "/api/me").body());
        HttpResponse<String> countries = api.get(alice, "/api/countries");

        assertThat(me).containsEntry("buyer_country", Map.of("country", "IN", "currency", "INR", "name", "India"));
        assertThat(countries.statusCode()).as(countries.body()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) LenientJson.readObject(countries.body())
                .get("countries");
        assertThat(all).hasSizeGreaterThan(200)
                .contains(Map.of("country", "IN", "name", "India", "currency", "INR"),
                        Map.of("country", "US", "name", "United States", "currency", "USD"));
        assertThat(all).extracting(c -> (String) c.get("name")).isSorted();
        assertThat(api.get("/api/countries").statusCode()).isEqualTo(401);
    }

    // Covers: 23.1, 6.2, 10 (V13) (ship_to_country IN is stored on the run and on the intent the run proposes; a run
    // without one gets the operator's default)
    @Test
    void shipToCountry_isStoredOnTheRun_andOnItsIntent_elseTheDefault() throws IOException {
        start(Map.of("BUYER_COUNTRY", "GB"));
        Session alice = api.signIn("alice");

        String india = runId(api.post(alice, "/api/runs", "{\"message\":\"hi\",\"ship_to_country\":\"IN\"}"));
        Map<String, Object> answered = LenientJson.readObject(
                api.post(alice, "/api/runs/" + india + "/messages", "{\"message\":\"a blue mug\"}").body());
        String intent = (String) ((List<?>) answered.get("intent_ids")).getFirst();
        Session bob = api.signIn("bob");
        String plain = runId(api.post(bob, "/api/runs", "{\"message\":\"hi\"}"));

        assertThat(runCountry("alice", india)).contains(new BuyerCountry("IN"));
        assertThat(service.bean(IntentRepository.class).find(new UserId("local-alice"), new IntentId(intent))
                .orElseThrow().shipTo()).isEqualTo(new BuyerCountry("IN"));
        assertThat(runCountry("bob", plain)).contains(new BuyerCountry("GB"));
    }

    // Covers: 23.1 (a country that is not two upper-case letters naming a known country is 422 validation_failed, and
    // so is a budget in another currency than the country's, with a message naming both; nothing is started)
    @Test
    void anUnknownCountry_orABudgetInAnotherCurrency_is422() throws IOException {
        start(Map.of());
        Session alice = api.signIn("alice");

        for (String bad : List.of("\"in\"", "\"XX\"", "\"IND\"", "5", "null", "\"AQ\"")) {
            HttpResponse<String> refused = api.post(alice, "/api/runs",
                    "{\"message\":\"hi\",\"ship_to_country\":" + bad + "}");
            assertThat(refused.statusCode()).as(bad).isEqualTo(422);
            assertThat(refused.body()).as(bad).contains("validation_failed");
        }
        HttpResponse<String> mismatch = api.post(alice, "/api/runs",
                "{\"message\":\"hi\",\"budget_minor\":50000,\"currency\":\"USD\",\"ship_to_country\":\"IN\"}");
        assertThat(mismatch.statusCode()).isEqualTo(422);
        assertThat(LenientJson.readObject(mismatch.body())).containsEntry("message",
                "the budget's currency must be INR, the currency of India");
        HttpResponse<String> matching = api.post(alice, "/api/runs",
                "{\"message\":\"hi\",\"budget_minor\":5000000,\"currency\":\"INR\",\"ship_to_country\":\"IN\"}");
        assertThat(matching.statusCode()).as(matching.body()).isEqualTo(201);
    }

    // Covers: 23.1 (the country is read from the body only: a country named in the message, or an Accept-Language or
    // forwarding header, changes nothing)
    @Test
    void theCountry_isNeverReadFromTheMessageOrAHeader() throws IOException {
        start(Map.of());
        Session alice = api.signIn("alice");

        String run = runId(api.send("POST", "/api/runs", Optional.of("{\"message\":\"deliver to India, ship to IN\"}"),
                "Cookie", "ucplab_session=" + alice.cookie(), "X-CSRF-Token", alice.csrf(),
                "Content-Type", "application/json", "Accept-Language", "en-IN", "X-Forwarded-For", "203.0.113.7"));

        assertThat(runCountry("alice", run)).contains(BuyerCountry.US);
    }

    // Covers: 23.1 (the country is fixed for the run: a continuation naming one is 422 validation_failed)
    @Test
    void aContinuationNamingACountry_is422() throws IOException {
        start(Map.of());
        Session alice = api.signIn("alice");
        String run = runId(api.post(alice, "/api/runs", "{\"message\":\"hi\"}"));

        HttpResponse<String> refused = api.post(alice, "/api/runs/" + run + "/messages",
                "{\"message\":\"a blue mug\",\"ship_to_country\":\"IN\"}");

        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("validation_failed");
        assertThat(runCountry("alice", run)).contains(BuyerCountry.US);
    }
}
