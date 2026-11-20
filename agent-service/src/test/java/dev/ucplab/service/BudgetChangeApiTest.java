package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The owner's change of a run's budget (23.1 {@code PATCH /api/runs/{run_id}/budget}, P9): owner-only and
 * CSRF-protected, under {@code POST /api/runs}'s rules, applying to the run's later proposals; an intent proposed
 * earlier keeps the budget it was proposed under, and its approval is unchanged. Every run answer names the run's current
 * budget, so a page loaded again shows it.
 */
class BudgetChangeApiTest {

    @TempDir
    Path dir;

    private TestService service;
    private ApiClient api;

    private void start() throws IOException {
        service = TestService.start(dir, Map.of(), Optional.empty(), List.of(), Optional.empty(), Map.of());
        api = new ApiClient(service.port());
    }

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    private static String budgeted(String text, long minor, String currency) {
        return "{\"message\":\"" + text + "\",\"budget_minor\":" + minor + ",\"currency\":\"" + currency + "\"}";
    }

    private static String budget(long minor, String currency) {
        return "{\"budget_minor\":" + minor + ",\"currency\":\"" + currency + "\"}";
    }

    private String startRun(Session s, String body) {
        HttpResponse<String> created = api.post(s, "/api/runs", body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return (String) LenientJson.readObject(created.body()).get("run_id");
    }

    private Map<String, Object> run(Session s, String id) {
        return LenientJson.readObject(api.get(s, "/api/runs/" + id).body());
    }

    // Covers: 23.1 PATCH budget, P9 (every run answer names the run's budget; the owner's PATCH changes it and answers
    // {run_id, budget}; a fresh GET, as a page loaded again reads it, shows the new budget; an empty body clears it)
    @Test
    void theOwnersPatch_changesTheRunsBudget_andEveryAnswerShowsIt() throws IOException {
        start();
        Session alice = api.signIn("alice");
        HttpResponse<String> created = api.post(alice, "/api/runs", budgeted("hi", 1_200, "USD"));
        String id = (String) LenientJson.readObject(created.body()).get("run_id");
        assertThat(LenientJson.readObject(created.body()))
                .containsEntry("budget", Map.of("amount_minor", 1_200L, "currency", "USD"));

        HttpResponse<String> patched = api.patch(alice, "/api/runs/" + id + "/budget", budget(1_500_000, "USD"));

        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        assertThat(LenientJson.readObject(patched.body())).isEqualTo(Map.of("run_id", id,
                "budget", Map.of("amount_minor", 1_500_000L, "currency", "USD")));
        assertThat(run(alice, id)).containsEntry("budget", Map.of("amount_minor", 1_500_000L, "currency", "USD"));
        assertThat(run(api.signIn("alice"), id)).as("another page of the same user")
                .containsEntry("budget", Map.of("amount_minor", 1_500_000L, "currency", "USD"));

        assertThat(api.patch(alice, "/api/runs/" + id + "/budget", "{}").statusCode()).isEqualTo(200);
        assertThat(run(alice, id)).doesNotContainKey("budget");
    }

    // Covers: 23.1 PATCH budget, T6, 18.5, P9 (another user's run and an unknown run are 404; without the CSRF token the
    // write is refused; one field alone, a bad amount, a bad code and another currency than the run's country's are
    // 422; none of them changes the budget)
    @Test
    void aPatch_isOwnerOnly_csrfProtected_andValidated() throws IOException {
        start();
        Session alice = api.signIn("alice");
        Session bob = api.signIn("bob");
        String id = startRun(alice, budgeted("hi", 1_200, "USD"));

        assertThat(api.patch(bob, "/api/runs/" + id + "/budget", budget(9, "USD")).statusCode()).isEqualTo(404);
        assertThat(api.patch(alice, "/api/runs/run-missing/budget", budget(9, "USD")).statusCode()).isEqualTo(404);
        HttpResponse<String> noToken = api.send("PATCH", "/api/runs/" + id + "/budget", Optional.of(budget(9, "USD")),
                "Cookie", "ucplab_session=" + alice.cookie(), "Content-Type", "application/json");
        assertThat(noToken.statusCode()).isEqualTo(403);
        for (String bad : List.of("{\"budget_minor\":500}", "{\"currency\":\"USD\"}", budget(0, "USD"),
                budget(1_000_000_000_001L, "USD"), budget(500, "usd"), "{\"budget_minor\":\"500\",\"currency\":\"USD\"}")) {
            HttpResponse<String> r = api.patch(alice, "/api/runs/" + id + "/budget", bad);
            assertThat(r.statusCode()).as(bad).isEqualTo(422);
            assertThat(r.body()).as(bad).contains("validation_failed");
        }
        HttpResponse<String> otherCurrency = api.patch(alice, "/api/runs/" + id + "/budget", budget(500, "INR"));
        assertThat(otherCurrency.statusCode()).isEqualTo(422);
        assertThat(LenientJson.readObject(otherCurrency.body())).containsEntry("message",
                "the budget's currency must be USD, the currency of United States");
        assertThat(run(alice, id)).containsEntry("budget", Map.of("amount_minor", 1_200L, "currency", "USD"));
    }

    // Covers: 23.1 PATCH budget, 23.2, 9.2, P9 (an intent proposed before the change keeps its budget, and its pending
    // approval, snapshot and budget, is unchanged while the run's budget is the new one: the card's "earlier budget"
    // reads from both; the next proposal is under the new budget)
    @Test
    void anIntentProposedEarlier_keepsItsBudget_andItsApproval() throws IOException {
        start();
        Session alice = api.signIn("alice");
        String id = startRun(alice, budgeted("hi", 1_000_000, "USD"));
        Map<String, Object> answered = LenientJson.readObject(
                api.post(alice, "/api/runs/" + id + "/messages", "{\"message\":\"a blue mug\"}").body());
        String intent = (String) ((List<?>) answered.get("intent_ids")).getFirst();
        Map<String, Object> before = LenientJson.readObject(api.get(alice, "/api/intents/" + intent + "/approval").body());

        assertThat(api.patch(alice, "/api/runs/" + id + "/budget", budget(2_000_000, "USD")).statusCode()).isEqualTo(200);

        Map<String, Object> after = LenientJson.readObject(api.get(alice, "/api/intents/" + intent + "/approval").body());
        assertThat(after.get("snapshot")).isEqualTo(before.get("snapshot"));
        assertThat(after).containsEntry("budget", Map.of("amount_minor", 1_000_000L, "currency", "USD"));
        assertThat(LenientJson.readObject(api.get(alice, "/api/intents/" + intent).body()))
                .containsEntry("budget", Map.of("amount_minor", 1_000_000L, "currency", "USD"));
        assertThat(run(alice, id)).containsEntry("budget", Map.of("amount_minor", 2_000_000L, "currency", "USD"));
    }
}
