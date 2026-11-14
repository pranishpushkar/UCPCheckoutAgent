package dev.ucplab.service.run;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.core.json.LenientJson;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The scripted model of {@code AGENT_MODEL=scripted} (25.4): no key, no network, the same turns for the same
 * conversation. It reads only the conversation, never a counter, so one instance serves every run and every
 * continuation.
 *
 * <p>Since the user's last message: nothing yet means a request that is too short to search for is answered with
 * {@code ask_user}, and anything else with {@code search_catalog} on the user's words; after an answer it searches for
 * a mug; after a result with items it proposes one of the first; and after a proposal, or a search without items, it
 * says what happened. Words in a tool result are never copied into a call except the ids the merchant gave (6.4).
 */
public final class DemoModel implements ModelClient {

    static final int MIN_REQUEST = 4;
    private static final int MAX_QUERY = 200;

    @Override
    public ModelTurn generate(Conversation conversation, List<ToolSpec> tools) {
        Objects.requireNonNull(conversation, "conversation");
        List<Message> messages = conversation.messages();
        int last = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof Message.User) {
                last = i;
            }
        }
        String request = last >= 0 ? ((Message.User) messages.get(last)).text() : "";
        Message.ToolResult latest = null;
        for (int i = last + 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof Message.ToolResult r) {
                latest = r;
            }
        }
        if (latest == null) {
            String words = request.strip();
            return words.length() < MIN_REQUEST
                    ? call("ask_user", Map.of("question", "What would you like to buy?"))
                    : call("search_catalog", Map.of("query", words.substring(0, Math.min(words.length(), MAX_QUERY)),
                            "max_results", 3L));
        }
        return switch (latest.tool()) {
            case "ask_user" -> call("search_catalog", Map.of("query", "mug", "max_results", 3L));
            case "search_catalog" -> afterSearch(latest.json());
            case "propose_purchase" -> say("I asked for your approval. Open the intent to review the quote.");
            default -> say("Done.");
        };
    }

    private static ModelTurn afterSearch(String json) {
        try {
            if (LenientJson.readObject(json).get("items") instanceof List<?> items && !items.isEmpty()
                    && items.getFirst() instanceof Map<?, ?> first && first.get("merchant_id") instanceof String merchant
                    && first.get("item_id") instanceof String item) {
                return call("propose_purchase", Map.of("merchant_id", merchant,
                        "items", List.of(Map.of("item_id", item, "quantity", 1L)),
                        "rationale", "This matches your request."));
            }
        } catch (RuntimeException e) {
            // An unreadable result is treated as no result.
        }
        return say("I found nothing that matches. Tell me more about what you want.");
    }

    private static ModelTurn call(String tool, Map<String, Object> args) {
        return new ModelTurn("", List.of(new ToolCall("call-" + tool, tool, args)), Usage.NONE);
    }

    private static ModelTurn say(String text) {
        return new ModelTurn(text, List.of(), Usage.NONE);
    }
}
