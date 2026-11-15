package dev.ucplab.runtime;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClientContract;
import java.util.List;
import java.util.Map;

/** {@link ScriptedModel} runs the model client contract (H3 P4; 6.5, I6, 13.1). */
class ScriptedModelContractTest extends ModelClientContract {

    private static final List<dev.ucplab.core.agent.ToolSpec> TOOLS =
            List.of(tool("search_catalog"), tool("propose_purchase"), tool("ask_user"));

    private static final String SCRIPT = """
            {"name": "contract",
             "turns": [{"when": {"tool": "search_catalog"}, "text": "never reached"}],
             "rules": [{"when": {"tool": "propose_purchase"}, "text": "I asked for your approval."}]}""";

    private static final String CALLS = """
            {"name": "contract",
             "turns": [{"call": [{"tool": "search_catalog", "args": {"query": "blue mug", "max_results": 3}}]}]}""";

    @Override
    protected Scenario scenario(Situation situation) {
        Conversation asked = new Conversation("prompt", List.of(new Message.User("buy a blue mug")));
        return switch (situation) {
            case CALLS_A_TOOL -> new Scenario(ScriptedModel.parse(CALLS), asked, TOOLS, "search_catalog",
                    Map.of("query", "blue mug", "max_results", 3L));
            case ANSWERS_WITH_TEXT -> new Scenario(ScriptedModel.parse(SCRIPT), asked.append(
                    new Message.ToolResult("call-1", "propose_purchase", "{\"state\":\"AWAITING_APPROVAL\"}")), TOOLS,
                    "", Map.of());
            // The only turn waits for a search result that the conversation does not have: the script has drifted.
            case CANNOT_ANSWER -> new Scenario(ScriptedModel.parse(SCRIPT), asked, TOOLS, "", Map.of());
        };
    }
}
