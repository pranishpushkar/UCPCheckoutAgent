package dev.ucplab.service.run;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClientContract;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.ToolSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;

/** {@link DemoModel} runs the model client contract (H3 P4; 6.5, I6, 25.4). */
class DemoModelContractTest extends ModelClientContract {

    private static final List<ToolSpec> TOOLS = List.of(tool("search_catalog"), tool("propose_purchase"), tool("ask_user"));

    @Override
    protected Scenario scenario(Situation situation) {
        Conversation asked = new Conversation("prompt", List.of(new Message.User("find a blue mug")));
        switch (situation) {
            case CALLS_A_TOOL:
                return new Scenario(new DemoModel(), asked, TOOLS, "search_catalog",
                        Map.of("query", "find a blue mug", "max_results", 3L));
            case ANSWERS_WITH_TEXT:
                return new Scenario(new DemoModel(), asked
                        .append(new Message.Model("", List.of(new ToolCall("c1", "propose_purchase", Map.of()))))
                        .append(new Message.ToolResult("c1", "propose_purchase", "{\"state\":\"AWAITING_APPROVAL\"}")),
                        TOOLS, "", Map.of());
            default:
                Assumptions.abort("DemoModel answers every conversation; it has no failure");
                throw new AssertionError("unreachable");
        }
    }
}
