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

    /** As {@link #start}, under caps that leave P9 the only limit a quote of a few hundred dollars meets (9.1). */
    private void startWithRoomUnderTheCaps() throws IOException {
        Path policy = java.nio.file.Files.writeString(dir.resolve("policy.yaml"), """
                per_purchase_cap_minor: 100000
                daily_cap_minor: 200000
                """);
        service = TestService.start(dir, Map.of("POLICY_FILE", policy.toString()), Optional.empty(), List.of(),
                Optional.empty(), Map.of());
        api = new ApiClient(service.port());
    }

    /** Starts a run under {@code body}, answers the question with "a blue mug", and returns the answer. */
    private Map<String, Object> answeredUnder(Session s, String body) {
        HttpResponse<String> created = api.post(s, "/api/runs", body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String run = (String) LenientJson.readObject(created.body()).get("run_id");
        return LenientJson.readObject(api.post(s, "/api/runs/" + run + "/messages", message("a blue mug")).body());
    }

    private static String lastMessage(Map<String, Object> answer) {
        List<?> messages = (List<?>) answer.get("messages");
        return (String) ((Map<?, ?>) messages.getLast()).get("text");
    }

    // Covers: P9, 9.1, 23.1, 23.2, 24.3, S29 (units, end to end: the 750 a user types in the USD field is sent as
    // budget_minor 75000; under it a quote of 699.95 opens the approval, and one of 785.95 is refused with policy_P9.
    // The intent answer carries the budget and the refused total in minor units, and the agent's reply names both
    // amounts and claims no approval. The lab merchant adds 8% tax, so the prices are chosen for those totals)
    @Test
    void a750UsdBudget_admitsA69995Quote_andRefusesA78595Quote() throws IOException {
        startWithRoomUnderTheCaps();
        Session alice = api.signIn("alice");
        Session bob = api.signIn("bob");
        Map<String, Object> budget = Map.of("amount_minor", 75_000L, "currency", "USD");

        service.merchant.setPrice("item_456", 64_810);
        Map<String, Object> fits = answeredUnder(alice, budgeted("hi", 75_000, "USD"));
        String fitting = (String) ((List<?>) fits.get("intent_ids")).getFirst();
        Map<String, Object> admitted = LenientJson.readObject(api.get(alice, "/api/intents/" + fitting).body());
        assertThat(admitted).containsEntry("state", "AWAITING_APPROVAL").containsEntry("budget", budget)
                .doesNotContainKey("refused_quote");
        @SuppressWarnings("unchecked")
        Map<String, Object> quote = (Map<String, Object>) admitted.get("quote");
        assertThat(quote).containsEntry("total_minor", 69_995L).containsEntry("currency", "USD");
        assertThat(lastMessage(fits)).isEqualTo("I asked for your approval. Open the intent to review the quote.");

        service.merchant.setPrice("item_456", 72_773);
        Map<String, Object> over = answeredUnder(bob, budgeted("hi", 75_000, "USD"));
        String refusedId = (String) ((List<?>) over.get("intent_ids")).getFirst();
        Map<String, Object> refused = LenientJson.readObject(api.get(bob, "/api/intents/" + refusedId).body());
        assertThat(refused).containsEntry("state", "FAILED_NOT_CHARGED").containsEntry("failure_reason", "policy_P9")
                .containsEntry("budget", budget)
                .containsEntry("refused_quote", Map.of("currency", "USD", "total_minor", 78_595L))
                .doesNotContainKey("approval");
        assertThat(lastMessage(over)).isEqualTo("Over your budget of $750.00; the closest I found was $785.95");
        assertThat(service.merchant.orders()).isEmpty();
    }

    // Covers: 23.2, P9, I9 (an intent without a budget has no budget and no refused_quote; another user's intent is
    // not shown at all)
    @Test
    void anIntentWithoutABudget_hasNeither_andAnotherUserSeesNothing() throws IOException {
        start(Map.of());
        Session alice = api.signIn("alice");
        String id = proposedUnder(alice, message("hi"));

        assertThat(LenientJson.readObject(api.get(alice, "/api/intents/" + id).body()))
                .doesNotContainKey("budget").doesNotContainKey("refused_quote");
        assertThat(api.get(api.signIn("bob"), "/api/intents/" + id).statusCode()).isEqualTo(404);
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
