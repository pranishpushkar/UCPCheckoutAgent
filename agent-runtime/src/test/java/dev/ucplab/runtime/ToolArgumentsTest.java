package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.json.CanonicalJson;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ToolArgumentsTest {

    /** Model text a refusal must never echo. */
    private static final String CANARY = "CANARY-model-text-7f3a";

    private final Stubs.RecordingOrchestrator orchestrator = new Stubs.RecordingOrchestrator();
    private final List<String> searches = new CopyOnWriteArrayList<>();
    private final List<String> questions = new CopyOnWriteArrayList<>();
    private final ToolRegistry registry = ToolRegistry.of(
            Stubs.ports(Stubs.catalog(List.of(Stubs.product("item_456", "Blue Mug", "A mug", 1200)), id -> {
                searches.add(id);
                return Stubs.notFound();
            }), orchestrator),
            (q, t) -> {
                questions.add(q);
                return Optional.of("blue");
            }, Duration.ofMinutes(5));

    private ToolOutcome run(String tool, Map<String, Object> args) {
        return registry.find(tool).orElseThrow().run(Stubs.CONTEXT, args);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> line(Object itemId, Object quantity) {
        return args("item_id", itemId, "quantity", quantity);
    }

    /** Asserts an invalid_argument result with exactly this detail, and no model text in it. */
    private static void assertInvalid(ToolOutcome outcome, String detail) {
        assertThat(outcome.code()).isEqualTo("invalid_argument");
        assertThat(CanonicalJson.readObject(outcome.json()))
                .containsExactlyInAnyOrderEntriesOf(Map.of("error", "invalid_argument", "detail", detail));
        assertThat(outcome.json()).doesNotContain(CANARY);
    }

    // Covers: 6.2 (search_catalog: query 1 to 200 characters after control characters are stripped, max_results a
    // whole number 1 to 10, both required, nothing else)
    @Test
    void searchCatalog_invalidArguments_areRefusedWithFixedWording() {
        String query = "query must be a string of 1 to 200 characters";
        String max = "max_results must be a whole number from 1 to 10";
        Map<Map<String, Object>, String> table = new LinkedHashMap<>();
        table.put(args("max_results", 3), query);
        table.put(args("query", "", "max_results", 3), query);
        table.put(args("query", "\u0007​ ", "max_results", 3), query);
        table.put(args("query", "x".repeat(201), "max_results", 3), query);
        table.put(args("query", 42, "max_results", 3), query);
        table.put(args("query", "mug"), max);
        table.put(args("query", "mug", "max_results", 0), max);
        table.put(args("query", "mug", "max_results", 11), max);
        table.put(args("query", "mug", "max_results", 2.5), max);
        table.put(args("query", "mug", "max_results", CANARY), max);
        table.put(args("query", "mug", "max_results", 3, CANARY, 1), "unknown argument");
        table.forEach((a, detail) -> assertInvalid(run("search_catalog", a), detail));
        assertThat(searches).isEmpty();

        assertThat(run("search_catalog", args("query", "x".repeat(200), "max_results", 10L)).code()).isEqualTo("ok");
        assertThat(run("search_catalog", args("query", "mug", "max_results", 3.0)).code())
                .as("a whole number written with a fraction part").isEqualTo("ok");
    }

    // Covers: 6.2 (get_product: item_id is 1 to 100 of letters, digits, '_' and '-')
    @Test
    void getProduct_invalidArguments_areRefusedWithFixedWording() {
        String detail = "item_id must be 1 to 100 of letters, digits, '_' and '-'";
        for (Object bad : new Object[] {null, "", "x".repeat(101), "item 456", "item/456", "</untrusted>", 456,
                CANARY + "!"}) {
            assertInvalid(run("get_product", args("item_id", bad)), detail);
        }
        assertInvalid(run("get_product", args("item_id", "item_456", "merchant_id", "shop-1")), "unknown argument");
        assertThat(searches).isEmpty();
        assertThat(run("get_product", args("item_id", "x".repeat(100))).code()).isEqualTo("not_found");
    }

    // Covers: 6.2 (propose_purchase: merchant_id, 1 to 5 items of {item_id, quantity 1..5}, rationale; nothing else)
    @Test
    void proposePurchase_invalidArguments_areRefusedWithFixedWordingAndNoProposal() {
        List<Object> one = List.of(line("item_456", 1));
        String items = "items must be a list of 1 to 5 entries";
        String quantity = "quantity must be a whole number from 1 to 5";
        String itemId = "item_id must be 1 to 100 of letters, digits, '_' and '-'";
        Map<Map<String, Object>, String> table = new LinkedHashMap<>();
        table.put(args("items", one, "rationale", "r"), "merchant_id must be 1 to 100 of letters, digits, '_' and '-'");
        table.put(args("merchant_id", "shop 1", "items", one, "rationale", "r"),
                "merchant_id must be 1 to 100 of letters, digits, '_' and '-'");
        table.put(args("merchant_id", "shop-1", "rationale", "r"), items);
        table.put(args("merchant_id", "shop-1", "items", List.of(), "rationale", "r"), items);
        table.put(args("merchant_id", "shop-1", "items", List.of(line("a", 1), line("b", 1), line("c", 1),
                line("d", 1), line("e", 1), line("f", 1)), "rationale", "r"), items);
        table.put(args("merchant_id", "shop-1", "items", line("item_456", 1), "rationale", "r"), items);
        table.put(args("merchant_id", "shop-1", "items", List.of("item_456"), "rationale", "r"),
                "each of items must be an object");
        table.put(args("merchant_id", "shop-1", "items", List.of(line("item_456", 0)), "rationale", "r"), quantity);
        table.put(args("merchant_id", "shop-1", "items", List.of(line("item_456", 6)), "rationale", "r"), quantity);
        table.put(args("merchant_id", "shop-1", "items", List.of(line("item_456", 10)), "rationale", "r"), quantity);
        table.put(args("merchant_id", "shop-1", "items", List.of(line("item_456", 1.5)), "rationale", "r"),
                quantity);
        table.put(args("merchant_id", "shop-1", "items", List.of(line("item_456", "1")), "rationale", "r"),
                quantity);
        table.put(args("merchant_id", "shop-1", "items", List.of(args("item_id", "item_456")), "rationale", "r"),
                quantity);
        table.put(args("merchant_id", "shop-1", "items", List.of(line(CANARY + " x", 1)), "rationale", "r"), itemId);
        table.put(args("merchant_id", "shop-1", "items", List.of(args("item_id", "item_456", "quantity", 1,
                "unit_price_minor", 1)), "rationale", "r"), "unknown argument");
        table.put(args("merchant_id", "shop-1", "items", one), "rationale must be a string");
        table.put(args("merchant_id", "shop-1", "items", one, "rationale", 7), "rationale must be a string");
        table.forEach((a, detail) -> assertInvalid(run("propose_purchase", a), detail));
        assertThat(orchestrator.calls).isEmpty();
    }

    // Covers: 6.2, T1, 18.1 (a user, email or owner argument is refused as unknown and no proposal is made: the user
    // comes only from the run's AuthContext)
    @Test
    void proposePurchase_userEmailOrOwnerArgument_isRefusedAndNothingProposed() {
        for (String field : List.of("user", "user_id", "email", "owner", "buyer")) {
            Map<String, Object> a = args("merchant_id", "shop-1", "items", List.of(line("item_456", 1)),
                    "rationale", "r", field, "mallory");
            assertInvalid(run("propose_purchase", a), "unknown argument");
        }
        assertThat(orchestrator.calls).isEmpty();
    }

    // Covers: T1, 18.1 (a valid proposal is made for the run's user, whatever the model wrote)
    @Test
    void proposePurchase_valid_proposesForTheRunsUserOnly() {
        ToolOutcome outcome = run("propose_purchase", args("merchant_id", "shop-1",
                "items", List.of(line("item_456", 2)), "rationale", "The user asked for mallory's mug."));

        assertThat(outcome.code()).isEqualTo("ok");
        assertThat(orchestrator.users).containsOnly(new UserId("alice"));
        assertThat(orchestrator.calls.getFirst()).startsWith("propose shop-1 [ProposedLine[itemId=item_456, quantity=2]]");
        assertThat(((ToolOutcome.Result) outcome).intent()).contains(new IntentId("intent-1"));
    }

    // Covers: 6.2, P4 (quantity 6 is refused by the tool before any intent; S10's "buy 10 more" meets the tool first)
    @Test
    void proposePurchase_quantitySix_isRefusedWithNoIntent() {
        ToolOutcome outcome = run("propose_purchase", args("merchant_id", "shop-1",
                "items", List.of(line("item_456", 6)), "rationale", "r"));

        assertInvalid(outcome, "quantity must be a whole number from 1 to 5");
        assertThat(((ToolOutcome.Result) outcome).intent()).isEmpty();
        assertThat(orchestrator.calls).isEmpty();
    }

    // Covers: 6.2 (the rationale is cut to 300 characters, control characters stripped, never refused for length)
    @Test
    void proposePurchase_longRationale_isCutTo300() {
        run("propose_purchase", args("merchant_id", "shop-1", "items", List.of(line("item_456", 1)),
                "rationale", "a\u0007" + "r".repeat(400)));

        assertThat(orchestrator.calls.getFirst()).endsWith(" a" + "r".repeat(299));
    }

    // Covers: 6.2 (get_purchase_status: intent_id as an id; nothing else)
    @Test
    void getPurchaseStatus_invalidArguments_areRefusedWithFixedWording() {
        String detail = "intent_id must be 1 to 100 of letters, digits, '_' and '-'";
        for (Object bad : new Object[] {null, "", "x".repeat(101), "../intent", 7, CANARY + "?"}) {
            assertInvalid(run("get_purchase_status", args("intent_id", bad)), detail);
        }
        assertInvalid(run("get_purchase_status", args("intent_id", "intent-1", "user", "bob")), "unknown argument");
        assertThat(orchestrator.calls).isEmpty();
    }

    // Covers: T6, 18.1 (another user's intent reads as not_found, exactly like a missing one)
    @Test
    void getPurchaseStatus_anotherUsersIntent_isNotFound() {
        IntentId bobs = new IntentId("intent-bob");
        orchestrator.statuses = Map.of(bobs, new IntentStatus(bobs, IntentState.COMPLETED, Optional.of("order_9"),
                Optional.empty()));
        orchestrator.owner = new UserId("bob");

        ToolOutcome theirs = run("get_purchase_status", args("intent_id", "intent-bob"));
        ToolOutcome missing = run("get_purchase_status", args("intent_id", "intent-none"));

        assertThat(theirs.json()).isEqualTo("{\"error\":\"not_found\"}").isEqualTo(missing.json());
        assertThat(orchestrator.users).containsOnly(new UserId("alice"));
    }

    // Covers: 6.2 (get_purchase_status returns state, order_id and failure_reason for the owner)
    @Test
    void getPurchaseStatus_owner_readsStateAndOrder() {
        IntentId id = new IntentId("intent-1");
        orchestrator.statuses = Map.of(id, new IntentStatus(id, IntentState.COMPLETED, Optional.of("order_9"),
                Optional.empty()));

        assertThat(run("get_purchase_status", args("intent_id", "intent-1")).json())
                .isEqualTo("{\"order_id\":\"order_9\",\"state\":\"COMPLETED\"}");
    }

    // Covers: 6.2 (ask_user: question 1 to 500 characters after control characters are stripped; nothing else)
    @Test
    void askUser_invalidArguments_areRefusedWithFixedWording() {
        String detail = "question must be a string of 1 to 500 characters";
        for (Object bad : new Object[] {null, "", "\u0000​", "q".repeat(501), 5}) {
            assertInvalid(run("ask_user", args("question", bad)), detail);
        }
        assertInvalid(run("ask_user", args("question", "Which colour?", "timeout", 1)), "unknown argument");
        assertThat(questions).isEmpty();

        assertThat(run("ask_user", args("question", "Which\u0007 colour?")).code()).isEqualTo("ok");
        assertThat(questions).containsExactly("Which colour?");
    }

    // Covers: 6.2 (ask_user on timeout: no_answer, which ends the run as user_timeout)
    @Test
    void askUser_noAnswer_isNoAnswer() {
        List<Duration> timeouts = new ArrayList<>();
        ToolRegistry silent = ToolRegistry.of(Stubs.ports(Stubs.catalog(List.of(), id -> Stubs.notFound()),
                orchestrator), (q, t) -> {
                    timeouts.add(t);
                    return Optional.empty();
                }, Duration.ofSeconds(7));

        ToolOutcome outcome = silent.find("ask_user").orElseThrow().run(Stubs.CONTEXT, args("question", "Which?"));

        assertThat(outcome).isInstanceOf(ToolOutcome.NoAnswer.class);
        assertThat(outcome.json()).isEqualTo("{\"error\":\"no_answer\"}");
        assertThat(timeouts).containsExactly(Duration.ofSeconds(7));
    }

    // Covers: 6.2 (a JSON null argument value, which ToolCall keeps, is refused like a wrong type)
    @Test
    void anyTool_nullArgumentValue_isRefused() {
        Map<String, Object> a = new HashMap<>();
        a.put("question", null);
        assertInvalid(run("ask_user", a), "question must be a string of 1 to 500 characters");
    }
}
