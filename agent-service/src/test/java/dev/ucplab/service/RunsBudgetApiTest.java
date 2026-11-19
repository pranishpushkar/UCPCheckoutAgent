package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.service.ApiClient.Session;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The user's budget on the runs API (23.1, 23.2, P9). The demo model asks, searches for a mug and proposes it. */
class RunsBudgetApiTest {

    @TempDir
    Path dir;

    private TestService service;
    private ApiClient api;

    private void start(Map<String, Object> singletons) throws IOException {
        service = TestService.start(dir, Map.of(), Optional.empty(), List.of(), Optional.empty(), singletons);
        api = new ApiClient(service.port());
    }

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    private static String message(String text) {
        return "{\"message\":\"" + text + "\"}";
    }

    private static String budgeted(String text, long minor, String currency) {
        return "{\"message\":\"" + text + "\",\"budget_minor\":" + minor + ",\"currency\":\"" + currency + "\"}";
    }

    /** Starts a run under {@code body}, answers the question, and returns the intent the demo model proposed. */
    private String proposedUnder(Session s, String body) {
        HttpResponse<String> created = api.post(s, "/api/runs", body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String run = (String) LenientJson.readObject(created.body()).get("run_id");
        Map<String, Object> answered = LenientJson.readObject(
                api.post(s, "/api/runs/" + run + "/messages", message("a blue mug")).body());
        return (String) ((List<?>) answered.get("intent_ids")).getFirst();
    }

    // Covers: 23.1, P9 (a budget is both fields or neither, an integer from 1 to 10^12 and three upper-case letters;
    // anything else is 422 validation_failed, and the bounds themselves are accepted)
    @Test
    void aBadBudget_is422_andTheBoundsAreAccepted() throws IOException {
        start(Map.of("testModel", (dev.ucplab.core.agent.ModelClient) (c, t) -> new ModelTurn("ok", List.of(), Usage.NONE)));
        Session alice = api.signIn("alice");

        for (String body : List.of(
                "{\"message\":\"hi\",\"budget_minor\":500}",
                "{\"message\":\"hi\",\"currency\":\"USD\"}",
                budgeted("hi", 0, "USD"), budgeted("hi", -1, "USD"), budgeted("hi", 1_000_000_000_001L, "USD"),
                budgeted("hi", 500, "usd"), budgeted("hi", 500, "US"), budgeted("hi", 500, "USDX"),
                "{\"message\":\"hi\",\"budget_minor\":\"500\",\"currency\":\"USD\"}",
                "{\"message\":\"hi\",\"budget_minor\":5.5,\"currency\":\"USD\"}",
                "{\"message\":\"hi\",\"budget_minor\":500,\"currency\":5}")) {
            HttpResponse<String> r = api.post(alice, "/api/runs", body);
            assertThat(r.statusCode()).as(body).isEqualTo(422);
            assertThat(r.body()).contains("validation_failed");
        }
        assertThat(api.post(alice, "/api/runs", budgeted("hi", 1, "USD")).statusCode()).isEqualTo(201);
        assertThat(api.post(alice, "/api/runs", budgeted("hi", 1_000_000_000_000L, "EUR")).statusCode()).isEqualTo(201);
        assertThat(api.post(alice, "/api/runs", message("hi")).statusCode()).isEqualTo(201);
    }

    // Covers: 23.1, P9 (the budget is fixed for the run: a budget field on a continuation is 422, and the run keeps its
    // budget, so the next proposal is still refused under it)
    @Test
    void aBudgetOnAContinuation_is422_andTheRunKeepsItsBudget() throws IOException {
        start(Map.of());
        Session alice = api.signIn("alice");
        String run = (String) LenientJson.readObject(api.post(alice, "/api/runs", budgeted("hi", 1, "USD")).body())
                .get("run_id");

        for (String body : List.of(budgeted("a blue mug", 1_000_000, "USD"),
                "{\"message\":\"a blue mug\",\"budget_minor\":1000000}", "{\"message\":\"a blue mug\",\"currency\":\"EUR\"}")) {
            HttpResponse<String> r = api.post(alice, "/api/runs/" + run + "/messages", body);
            assertThat(r.statusCode()).as(body).isEqualTo(422);
            assertThat(r.body()).contains("validation_failed");
        }
        Map<String, Object> answered = LenientJson.readObject(
                api.post(alice, "/api/runs/" + run + "/messages", message("a blue mug")).body());
        String intent = (String) ((List<?>) answered.get("intent_ids")).getFirst();

        Map<String, Object> stored = LenientJson.readObject(api.get(alice, "/api/intents/" + intent).body());
        assertThat(stored).containsEntry("state", "FAILED_NOT_CHARGED").containsEntry("failure_reason", "policy_P9");
    }

    // Covers: 23.2, P9, 9.2 (the pending approval carries the intent's budget at the top level, outside the snapshot;
    // without a budget it has none)
    @Test
    void theApproval_carriesTheBudget_outsideTheSnapshot() throws IOException {
        start(Map.of());
        Session alice = api.signIn("alice");

        String withBudget = proposedUnder(alice, budgeted("hi", 1_000_000_000_000L, "USD"));
        Map<String, Object> approval = LenientJson.readObject(api.get(alice, "/api/intents/" + withBudget + "/approval").body());
        assertThat(approval).containsEntry("budget", Map.of("amount_minor", 1_000_000_000_000L, "currency", "USD"));
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) approval.get("snapshot");
        assertThat(snapshot).doesNotContainKey("budget");

        String without = proposedUnder(api.signIn("bob"), message("hi"));
        assertThat(LenientJson.readObject(api.get(api.signIn("bob"), "/api/intents/" + without + "/approval").body()))
                .doesNotContainKey("budget");
    }

    // Covers: 6.4, 23.1, P9 (the model's context has the [BUDGET] paragraph after the unchanged system prompt, built
    // from the field, never the message; a run without a budget has no paragraph)
    @Test
    void theModelSeesTheBudgetAsAHint_builtFromTheField() throws IOException {
        List<String> prompts = new CopyOnWriteArrayList<>();
        start(Map.of("testModel", (dev.ucplab.core.agent.ModelClient) (c, t) -> {
            prompts.add(c.systemPrompt());
            return new ModelTurn("ok", List.of(), Usage.NONE);
        }));
        Session alice = api.signIn("alice");

        api.post(alice, "/api/runs", budgeted("a snowboard under $900", 50_000, "USD"));
        api.post(alice, "/api/runs", message("a snowboard under $900"));

        assertThat(prompts).hasSize(2);
        assertThat(prompts.getFirst()).startsWith(dev.ucplab.runtime.SystemPrompt.text())
                .endsWith("[BUDGET] The user's budget for this run is $500.00. Propose only items whose total fits it;"
                        + " a quote above it is refused.")
                .doesNotContain("$900");
        assertThat(prompts.get(1)).isEqualTo(dev.ucplab.runtime.SystemPrompt.text());
    }

    // Covers: P9, 6.2, T1 (no model output can set, raise or clear the budget: a propose_purchase carrying budget
    // arguments is refused as invalid, and a plain one is still held to the run's budget)
    @Test
    void modelOutput_cannotSetRaiseOrClearTheBudget() throws IOException {
        List<String> results = new CopyOnWriteArrayList<>();
        start(Map.of("testModel", (dev.ucplab.core.agent.ModelClient) (c, t) -> {
            long calls = c.messages().stream().filter(m -> m instanceof dev.ucplab.core.agent.Message.ToolResult).count();
            c.messages().stream().filter(m -> m instanceof dev.ucplab.core.agent.Message.ToolResult)
                    .map(m -> ((dev.ucplab.core.agent.Message.ToolResult) m).json()).skip(results.size())
                    .forEach(results::add);
            Map<String, Object> plain = Map.of("merchant_id", "merchant-a",
                    "items", List.of(Map.of("item_id", "item_123", "quantity", 1L)), "rationale", "fits");
            if (calls == 0) {
                Map<String, Object> raised = new java.util.HashMap<>(plain);
                raised.put("budget_minor", 1_000_000_000L);
                raised.put("currency", "USD");
                return new ModelTurn("", List.of(new ToolCall("c1", "propose_purchase", raised)), Usage.NONE);
            }
            if (calls == 1) {
                Map<String, Object> cleared = new java.util.HashMap<>(plain);
                cleared.put("budget", "none");
                return new ModelTurn("", List.of(new ToolCall("c2", "propose_purchase", cleared)), Usage.NONE);
            }
            if (calls == 2) {
                return new ModelTurn("", List.of(new ToolCall("c3", "propose_purchase", plain)), Usage.NONE);
            }
            return new ModelTurn("done", List.of(), Usage.NONE);
        }));
        Session alice = api.signIn("alice");

        Map<String, Object> run = LenientJson.readObject(api.post(alice, "/api/runs", budgeted("a red t-shirt", 1, "USD")).body());

        assertThat(results).hasSize(3);
        assertThat(results.get(0)).contains("invalid_argument");
        assertThat(results.get(1)).contains("invalid_argument");
        assertThat(results.get(2)).contains("policy_P9").contains("over your budget of $0.01 (P9)");
        String intent = (String) ((List<?>) run.get("intent_ids")).getFirst();
        assertThat(LenientJson.readObject(api.get(alice, "/api/intents/" + intent).body()))
                .containsEntry("failure_reason", "policy_P9");
    }
}
