package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.Usage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScriptedModelTest {

    private static final String SEARCH_RESULT = "{\"items\":[{\"currency\":\"USD\",\"in_stock\":true,"
            + "\"item_id\":\"item_456\",\"merchant_id\":\"shop-1\",\"price_minor\":1200,\"title\":\"t\"}]}";
    private static final String AWAITING = "{\"intent_id\":\"i-1\",\"quote\":{\"currency\":\"USD\",\"lines\":[],"
            + "\"total_minor\":1200},\"state\":\"AWAITING_APPROVAL\"}";
    private static final String REJECTED = "{\"failure_reason\":\"policy_p1\",\"intent_id\":\"i-1\","
            + "\"state\":\"REJECTED\"}";

    /** A conversation as the runtime builds it: the user's message, then each turn and its results. */
    private static final class Talk {
        private final List<Message> messages = new ArrayList<>(List.of(new Message.User("buy a blue mug")));

        Conversation now() {
            return new Conversation("prompt", messages);
        }

        ModelTurn ask(ScriptedModel model) {
            ModelTurn turn = model.generate(now(), List.of());
            messages.add(new Message.Model(turn.text(), turn.toolCalls()));
            return turn;
        }

        void result(ToolCall call, String json) {
            messages.add(new Message.ToolResult(call.id(), call.name(), json));
        }
    }

    // Covers: 13.1, m5-plan section 7 (bad scripts are refused when read, naming the field)
    @Test
    void parse_badScripts_areRefused() {
        List<String> bad = List.of(
                "{\"turns\":[{\"text\":\"hi\"}]}",
                "{\"name\":\"x\",\"turns\":[]}",
                "{\"name\":\"x\"}",
                "{\"name\":\"x\",\"turns\":[{\"text\":\"hi\"}],\"extra\":1}",
                "{\"name\":\"x\",\"turns\":[{}]}",
                "{\"name\":\"x\",\"turns\":[{\"text\":\"hi\",\"cal\":[]}]}",
                "{\"name\":\"x\",\"turns\":[{\"call\":[]}]}",
                "{\"name\":\"x\",\"turns\":[{\"call\":[{\"args\":{}}]}]}",
                "{\"name\":\"x\",\"turns\":[{\"call\":[{\"tool\":\"t\",\"args\":{\"a\":null}}]}]}",
                "{\"name\":\"x\",\"turns\":[{\"text\":\"hi\"}],\"rules\":[{\"text\":\"no when\"}]}",
                "{\"name\":\"x\",\"turns\":[{\"when\":{\"tool\":\"t\",\"resultMatches\":\"(\"},\"text\":\"hi\"}]}",
                "{\"name\":\"x\",\"turns\":[{\"when\":{\"resultMatches\":\"a\"},\"text\":\"hi\"}]}",
                "{\"name\":\"x\",\"turns\":[{\"text\":\"hi\"}],\"usage\":{\"inputTokens\":-1}}",
                "{\"name\":\"x\",\"turns\":[{\"text\":\"hi\"}],\"usage\":{\"inputTokens\":1.5}}",
                "{\"name\":\"x\",\"turns\":[{\"text\":7}]}",
                "{\"name\":\"x\",\"turns\":[{\"text\":\"hi\"}],\"expectToolSequence\":\"search_catalog\"}",
                "not json");
        for (String script : bad) {
            assertThatThrownBy(() -> ScriptedModel.parse(script)).as(script)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // Covers: 6.5, 13.1 (turns answer in order; tool call ids are call-1, call-2; the last turn's text ends the run)
    @Test
    void turns_answerInOrder_withSequentialCallIds() {
        ScriptedModel model = ScriptedModel.fromResource(ScriptedModelTest.class, "scripts/buy-blue-mug.json");
        Talk talk = new Talk();

        ModelTurn first = talk.ask(model);
        assertThat(first.text()).isEmpty();
        assertThat(first.toolCalls()).containsExactly(new ToolCall("call-1", "search_catalog",
                Map.of("max_results", 3L, "query", "blue mug")));
        talk.result(first.toolCalls().getFirst(), SEARCH_RESULT);

        ModelTurn second = talk.ask(model);
        assertThat(second.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("call-2");
            assertThat(c.name()).isEqualTo("propose_purchase");
            assertThat(c.arguments()).containsEntry("merchant_id", "shop-1")
                    .containsEntry("items", List.of(Map.of("item_id", "item_456", "quantity", 1L)));
        });
        talk.result(second.toolCalls().getFirst(), AWAITING);

        ModelTurn third = talk.ask(model);
        assertThat(third.toolCalls()).isEmpty();
        assertThat(third.text()).isEqualTo("I have asked for your approval of 1 Blue Mug at 1200 minor units.");
        model.checkToolSequence();
        assertThat(model.calledTools()).containsExactly("search_catalog", "propose_purchase");
    }

    // Covers: 13.1 (rules are tried first, in order, the first match answers, and a rule uses no turn)
    @Test
    void rules_firstMatchAnswers_andUsesNoTurn() {
        ScriptedModel model = ScriptedModel.parse("""
                {"name": "rules",
                 "turns": [{"call": [{"tool": "propose_purchase", "args": {}}]}, {"text": "turn two"}],
                 "rules": [{"when": {"tool": "propose_purchase", "resultMatches": "REJECTED"}, "text": "rule one"},
                           {"when": {"tool": "propose_purchase"}, "text": "rule two"}]}""");
        Talk talk = new Talk();
        ModelTurn first = talk.ask(model);
        talk.result(first.toolCalls().getFirst(), REJECTED);

        assertThat(talk.ask(model).text()).isEqualTo("rule one");
        assertThat(talk.ask(model).text()).as("no result since the model's last turn: no rule, the next turn")
                .isEqualTo("turn two");
    }

    // Covers: 13.1 (templates: fields, [n], a whole-string template keeps the value's type, embedded ones are text)
    @Test
    void templates_fillFieldsIndexesAndTypes() {
        ScriptedModel model = ScriptedModel.parse("""
                {"name": "templates",
                 "turns": [{"call": [{"tool": "search_catalog", "args": {}}]},
                           {"when": {"tool": "search_catalog"},
                            "call": [{"tool": "get_product", "args": {"item_id": "${result.items[0].item_id}",
                                      "n": "${result.items[0].price_minor}",
                                      "nested": [{"m": "${result.items[0].merchant_id}"}],
                                      "s": "at ${result.items[0].price_minor} ${result.items[0].currency}"}}]}]}""");
        Talk talk = new Talk();
        talk.result(talk.ask(model).toolCalls().getFirst(), SEARCH_RESULT);

        Map<String, Object> args = talk.ask(model).toolCalls().getFirst().arguments();

        assertThat(args).containsEntry("item_id", "item_456").containsEntry("n", 1200L)
                .containsEntry("nested", List.of(Map.of("m", "shop-1"))).containsEntry("s", "at 1200 USD");
    }

    // Covers: 13.1 (drift: the next turn's when does not match and no rule does)
    @Test
    void drift_whenDoesNotMatch_throws() {
        ScriptedModel model = ScriptedModel.fromResource(ScriptedModelTest.class, "scripts/buy-blue-mug.json");
        Talk talk = new Talk();
        talk.result(talk.ask(model).toolCalls().getFirst(), "{\"items\":[]}");

        assertThatThrownBy(() -> talk.ask(model)).isInstanceOf(ScriptDriftException.class)
                .hasMessageContaining("buy-blue-mug").hasMessageContaining("turn 2's when did not match");
    }

    // Covers: 13.1 (drift: the turns run out while the runtime asks for another)
    @Test
    void drift_turnsRunOut_throws() {
        ScriptedModel model = ScriptedModel.parse("{\"name\":\"short\",\"turns\":[{\"text\":\"done\"}]}");
        Talk talk = new Talk();
        talk.ask(model);

        assertThatThrownBy(() -> talk.ask(model)).isInstanceOf(ScriptDriftException.class)
                .hasMessageContaining("the turns ran out");
    }

    // Covers: 13.1 (drift: a missing template path is an error, never an empty string; so is a template with no
    // result to read, or a path that ends on an object)
    @Test
    void drift_missingTemplatePath_throws() {
        for (String path : List.of("items[1].item_id", "items[0].colour", "quote.total_minor", "items[0]", "items",
                "items[0].item_id.x")) {
            ScriptedModel model = ScriptedModel.parse("{\"name\":\"t\",\"turns\":[{\"call\":[{\"tool\":\"s\"}]},"
                    + "{\"text\":\"${result." + path + "}\"}]}");
            Talk talk = new Talk();
            talk.result(talk.ask(model).toolCalls().getFirst(), SEARCH_RESULT);

            assertThatThrownBy(() -> talk.ask(model)).as(path).isInstanceOf(ScriptDriftException.class)
                    .hasMessageContaining("is missing");
        }
        ScriptedModel noResult = ScriptedModel.parse("{\"name\":\"t\",\"turns\":[{\"text\":\"${result.x}\"}]}");
        assertThatThrownBy(() -> new Talk().ask(noResult)).isInstanceOf(ScriptDriftException.class)
                .hasMessageContaining("has no tool result");
    }

    // Covers: 13.1 (drift: the tool sequence at the end differs from expectToolSequence)
    @Test
    void drift_toolSequenceDiffers_throwsOnCheck() {
        ScriptedModel model = ScriptedModel.parse("""
                {"name": "sequence", "expectToolSequence": ["search_catalog", "propose_purchase"],
                 "turns": [{"call": [{"tool": "search_catalog"}]}, {"text": "stopped early"}]}""");
        Talk talk = new Talk();
        talk.result(talk.ask(model).toolCalls().getFirst(), SEARCH_RESULT);
        talk.ask(model);

        assertThatThrownBy(model::checkToolSequence).isInstanceOf(ScriptDriftException.class)
                .hasMessageContaining("[search_catalog]");
    }

    // Covers: 13.1 (the same script and the same tool results give the same turns twice)
    @Test
    void sameScriptSameResults_sameTurnsTwice() {
        List<List<ModelTurn>> runs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ScriptedModel model = ScriptedModel.fromResource(ScriptedModelTest.class, "scripts/buy-blue-mug.json");
            Talk talk = new Talk();
            List<ModelTurn> turns = new ArrayList<>();
            ModelTurn t = talk.ask(model);
            turns.add(t);
            talk.result(t.toolCalls().getFirst(), SEARCH_RESULT);
            t = talk.ask(model);
            turns.add(t);
            talk.result(t.toolCalls().getFirst(), AWAITING);
            turns.add(talk.ask(model));
            runs.add(turns);
        }

        assertThat(runs.get(0)).isEqualTo(runs.get(1));
    }

    // Covers: 13.1, 13.3 (usage is 0/0 unless the script says otherwise; a turn's usage overrides the script's)
    @Test
    void usage_defaultsToZero_scriptAndTurnOverride() {
        assertThat(new Talk().ask(ScriptedModel.parse("{\"name\":\"u\",\"turns\":[{\"text\":\"a\"}]}")).usage())
                .isEqualTo(Usage.NONE);
        ScriptedModel model = ScriptedModel.parse("""
                {"name": "u", "usage": {"inputTokens": 10, "outputTokens": 2},
                 "turns": [{"text": "a"}, {"text": "b", "usage": {"inputTokens": 25000}}]}""");
        Talk talk = new Talk();

        assertThat(talk.ask(model).usage()).isEqualTo(new Usage(10, 2));
        assertThat(talk.ask(model).usage()).isEqualTo(new Usage(25000, 0));
    }

    // Covers: 13.1, risk 7 (a template carries a real UUIDv7 intent id from the orchestrator into the next call)
    @Test
    void template_carriesARealUuidV7IntentId() throws Exception {
        try (RuntimeLab lab = new RuntimeLab()) {
            ToolRegistry tools = ToolRegistry.of(lab.ports(), (q, t) -> Optional.empty(), Duration.ofMinutes(5));
            ToolContext context = new ToolContext(lab.auth("uuid-user"), new RunId("run-uuid"));
            ScriptedModel model = ScriptedModel.parse("""
                    {"name": "uuid",
                     "turns": [{"call": [{"tool": "propose_purchase", "args": {"merchant_id": "shop-1",
                                "items": [{"item_id": "item_456", "quantity": 1}], "rationale": "r"}}]},
                               {"when": {"tool": "propose_purchase"},
                                "call": [{"tool": "get_purchase_status",
                                          "args": {"intent_id": "${result.intent_id}"}}]}]}""");
            Talk talk = new Talk();
            ToolCall propose = talk.ask(model).toolCalls().getFirst();
            ToolOutcome proposed = tools.find(propose.name()).orElseThrow().run(context, propose.arguments());
            talk.result(propose, proposed.json());

            ToolCall status = talk.ask(model).toolCalls().getFirst();

            String intentId = (String) status.arguments().get("intent_id");
            assertThat(intentId).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                    .isEqualTo(((ToolOutcome.Result) proposed).intent().orElseThrow().value());
            assertThat(tools.find(status.name()).orElseThrow().run(context, status.arguments()).json())
                    .contains("\"state\":\"AWAITING_APPROVAL\"");
        }
    }
}
