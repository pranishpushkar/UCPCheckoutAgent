package dev.ucplab.service.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.ToolSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What {@link DemoModel} says after {@code propose_purchase}: what the tool result says, and nothing more (24.3). */
class DemoModelTest {

    private static final List<ToolSpec> TOOLS = List.of();

    private static String after(String result) {
        Conversation c = new Conversation("prompt", List.of(new Message.User("a snowboard under $750")))
                .append(new Message.Model("", List.of(new ToolCall("c1", "propose_purchase", Map.of()))))
                .append(new Message.ToolResult("c1", "propose_purchase", result));
        ModelTurn turn = new DemoModel().generate(c, TOOLS);
        assertThat(turn.toolCalls()).isEmpty();
        return turn.text();
    }

    // Covers: 24.3, 6.2 (an approval is said to be asked only when the result is AWAITING_APPROVAL)
    @Test
    void afterAnApproval_saysItAsked() {
        assertThat(after("{\"intent_id\":\"i1\",\"state\":\"AWAITING_APPROVAL\"}"))
                .isEqualTo("I asked for your approval. Open the intent to review the quote.");
    }

    // Covers: 24.3, 6.2, P9, S29 (after a P9 denial it relays the result: the budget and the quoted total, from the
    // result's numbers and in their own currencies, and no approval)
    @Test
    void afterAP9Denial_relaysTheBudgetAndTheQuotedTotal() {
        String said = after("{\"budget\":{\"amount_minor\":75000,\"currency\":\"USD\"},\"failure_reason\":\"policy_P9\","
                + "\"intent_id\":\"i1\",\"quoted\":{\"currency\":\"USD\",\"total_minor\":78595},"
                + "\"reason\":\"over your budget of $750.00 (P9)\",\"state\":\"FAILED_NOT_CHARGED\"}");

        assertThat(said).isEqualTo("Over your budget of $750.00; the closest I found was $785.95");
        assertThat(after("{\"budget\":{\"amount_minor\":75000,\"currency\":\"USD\"},\"failure_reason\":\"policy_P9\","
                + "\"intent_id\":\"i1\",\"quoted\":{\"currency\":\"EUR\",\"total_minor\":78595},"
                + "\"state\":\"FAILED_NOT_CHARGED\"}"))
                .isEqualTo("Over your budget of $750.00; the closest I found was EUR 785.95");
    }

    // Covers: 24.3, 6.2, 6.4 (after any other state, or a P9 result without both numbers, a fixed line: no approval
    // claimed, and no word or code of the result repeated)
    @Test
    void afterAnyOtherEnding_saysItStopped_withoutTheCode() {
        String stopped = "That purchase stopped before an approval, and no order was placed. Open the intent to see why.";
        for (String result : List.of(
                "{\"failure_reason\":\"out_of_stock\",\"intent_id\":\"i1\",\"state\":\"FAILED_NOT_CHARGED\"}",
                "{\"failure_reason\":\"policy_P1\",\"intent_id\":\"i1\",\"state\":\"REJECTED\"}",
                "{\"intent_id\":\"i1\",\"state\":\"ESCALATED\"}",
                "{\"failure_reason\":\"policy_P9\",\"intent_id\":\"i1\",\"state\":\"FAILED_NOT_CHARGED\"}",
                "not json")) {
            assertThat(after(result)).as(result).isEqualTo(stopped);
        }
    }
}
