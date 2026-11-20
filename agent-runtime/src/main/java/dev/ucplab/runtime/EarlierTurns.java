package dev.ucplab.runtime;

import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.money.MoneyFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 6.3 [CONTRACT] earlier turns in the context: what a continuation sends the model of the turns before it. User
 * messages, the agent's texts and its tool calls stay as they were; each earlier tool result becomes a one-line summary
 * of ours, {@code {"earlier": "<summary>"}}, except an {@code ask_user} answer, which is the user's own words. A title or
 * seller in a summary is the earlier result's own wrapped text (6.4); numbers and states are ours. A summary is kept as
 * it is, so the context grows with the number of earlier calls, never with what their results held.
 */
final class EarlierTurns {

    static final String EARLIER = "earlier";
    /** A code of ours, such as a state or a failure reason the model may see as it is (8.1). */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_]{1,80}");

    private EarlierTurns() {
    }

    /** The earlier turns' messages, each tool result summarised. */
    static List<Message> compact(List<Message> messages) {
        Map<String, ToolCall> calls = new HashMap<>();
        Map<String, Map<?, ?>> items = new HashMap<>();
        List<Message> out = new ArrayList<>(messages.size());
        for (Message message : messages) {
            switch (message) {
                case Message.Model model -> {
                    model.toolCalls().forEach(c -> calls.put(c.id(), c));
                    out.add(model);
                }
                case Message.ToolResult result -> out.add(summarised(result, Optional.ofNullable(calls.get(result.callId())),
                        items));
                case Message.User user -> out.add(user);
            }
        }
        return List.copyOf(out);
    }

    /** 6.3 superseded search results: what a run's next model call is sent of its own messages. */
    static List<Message> superseded(List<Message> messages) {
        return List.copyOf(messages);
    }

    private static Message summarised(Message.ToolResult result, Optional<ToolCall> call, Map<String, Map<?, ?>> items) {
        if (result.tool().equals("ask_user")) {
            return result;
        }
        Map<String, Object> json;
        try {
            json = CanonicalJson.readObject(result.json());
        } catch (RuntimeException e) {
            return summary(result, result.tool() + ": done", Optional.empty());
        }
        if (json.containsKey(EARLIER)) {
            return result;
        }
        if (json.get("error") instanceof String code) {
            return summary(result, "error: " + (CODE.matcher(code).matches() ? code : "unknown"), Optional.empty());
        }
        return switch (result.tool()) {
            case "search_catalog" -> {
                List<?> found = json.get("items") instanceof List<?> l ? l : List.of();
                found.forEach(i -> remember(i, items));
                StringBuilder text = new StringBuilder("searched: ").append(found.size())
                        .append(found.size() == 1 ? " result" : " results");
                if (json.get("above_budget") instanceof Map<?, ?> above) {
                    text.append("; ").append(above.get("count")).append(" above the budget, lowest ")
                            .append(amount(above, "lowest_price"));
                }
                yield summary(result, text.toString(), Optional.empty());
            }
            case "get_product" -> {
                // A search item names its seller and get_product does not: an item already seen keeps its search entry.
                if (json.get("item_id") instanceof String id) {
                    items.putIfAbsent(id, json);
                }
                yield summary(result, "read: " + json.get("title") + ", " + amount(json, "price"), Optional.empty());
            }
            case "propose_purchase" -> summary(result, proposed(json, call, items),
                    Optional.ofNullable(json.get("intent_id")).map(String::valueOf));
            case "get_purchase_status" -> summary(result, "status: intent " + state(json), Optional.empty());
            default -> summary(result, result.tool() + ": done", Optional.empty());
        };
    }

    /** {@code proposed: <title>, <total>, <seller>; intent <state> when proposed (<failure_reason>)}. */
    private static String proposed(Map<String, Object> json, Optional<ToolCall> call, Map<String, Map<?, ?>> items) {
        List<String> parts = new ArrayList<>();
        List<?> lines = call.map(c -> c.arguments().get("items")).filter(List.class::isInstance).map(List.class::cast)
                .orElse(List.of());
        Optional<Map<?, ?>> first = lines.stream().filter(Map.class::isInstance).map(l -> ((Map<?, ?>) l).get("item_id"))
                .findFirst().map(items::get);
        first.map(i -> i.get("title")).ifPresent(t -> parts.add(String.valueOf(t)));
        Optional<Map<?, ?>> priced = json.get("quote") instanceof Map<?, ?> q ? Optional.of(q)
                : json.get("quoted") instanceof Map<?, ?> q ? Optional.of(q) : Optional.empty();
        priced.map(p -> amount(p, "total")).ifPresent(parts::add);
        first.map(i -> i.get("seller")).ifPresent(s -> parts.add(String.valueOf(s)));
        StringBuilder text = new StringBuilder("proposed");
        if (!parts.isEmpty()) {
            text.append(": ").append(String.join(", ", parts));
        }
        if (lines.size() > 1) {
            text.append(" and ").append(lines.size() - 1).append(lines.size() == 2 ? " more item" : " more items");
        }
        text.append("; intent ").append(state(json)).append(" when proposed");
        if (json.get("failure_reason") instanceof String reason && CODE.matcher(reason).matches()) {
            text.append(" (").append(reason).append(')');
        }
        return text.toString();
    }

    /** An item's wrapped title and seller, by its item_id, for a later proposal's summary. */
    private static void remember(Object item, Map<String, Map<?, ?>> items) {
        if (item instanceof Map<?, ?> map && map.get("item_id") instanceof String id) {
            items.put(id, map);
        }
    }

    /** The amount as text beside its minor units; formatted from them when a result has no text twin (24.3). */
    private static String amount(Map<?, ?> map, String field) {
        if (map.get(field) instanceof String text) {
            return text;
        }
        if (map.get(field + "_minor") instanceof Number minor && map.get("currency") instanceof String currency) {
            return MoneyFormat.format(minor.longValue(), currency);
        }
        return "an unknown amount";
    }

    private static String state(Map<String, Object> json) {
        return json.get("state") instanceof String s && CODE.matcher(s).matches() ? s : "unknown";
    }

    private static Message.ToolResult summary(Message.ToolResult result, String text, Optional<String> intentId) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put(EARLIER, text);
        intentId.filter(i -> CODE.matcher(i.replace("-", "_")).matches()).ifPresent(i -> json.put("intent_id", i));
        return new Message.ToolResult(result.callId(), result.tool(), CanonicalJson.write(json));
    }
}
