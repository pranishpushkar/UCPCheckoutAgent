package dev.ucplab.runtime;

import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentNotFoundException;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.json.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code get_purchase_status} (6.2, read only): {@code intent_id}. Returns {@code {state, order_id?, failure_reason?}}
 * for an intent of the run's user. Another user's intent, like a missing one, is {@code {"error":"not_found"}} (T6).
 */
final class GetPurchaseStatusTool implements Tool {

    static final String NAME = "get_purchase_status";
    private static final ToolSpec SPEC = new ToolSpec(NAME,
            "Returns the state of a purchase intent, and its order id once completed.",
            CanonicalJson.write(Map.of(
                    "type", "object",
                    "properties", Map.of("intent_id", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,100}$",
                            "description", "The intent_id from propose_purchase.")),
                    "required", List.of("intent_id"),
                    "additionalProperties", false)));

    private final ToolPorts ports;

    GetPurchaseStatusTool(ToolPorts ports) {
        this.ports = Objects.requireNonNull(ports, "ports");
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolOutcome run(ToolContext context, Map<String, Object> arguments) {
        IntentId id;
        try {
            Args.only(arguments, Set.of("intent_id"));
            id = new IntentId(Args.id(arguments, "intent_id"));
        } catch (Args.Invalid e) {
            return ToolOutcome.invalid(e.getMessage());
        }
        IntentStatus status;
        try {
            status = ports.orchestrator().status(context.auth(), id);
        } catch (IntentNotFoundException e) {
            return ToolOutcome.error("not_found");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", status.state().name());
        status.orderId().ifPresent(o -> result.put("order_id", ToolText.merchantId("order_id", o)));
        status.failureReason().ifPresent(r -> result.put("failure_reason", ToolText.failureReason(r)));
        return ToolOutcome.ok(result);
    }
}
