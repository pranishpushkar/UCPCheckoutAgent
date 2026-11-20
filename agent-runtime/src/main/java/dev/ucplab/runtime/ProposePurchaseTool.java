package dev.ucplab.runtime;

import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code propose_purchase} (6.2): {@code merchant_id}, {@code items} (1 to 5 of {@code {item_id, quantity 1..5}}) and
 * {@code rationale} (cut to 300 characters). Calls {@code Orchestrator.propose} for the run's user, taken from the
 * {@link ToolContext} only: any other argument, such as a user, email or owner, is refused as unknown (T1, 18.1).
 * {@code merchant_id} is not checked against the registry here; policy decides (P1). A quantity above 5 is refused
 * before any intent exists, and P4 stays the second layer behind it.
 *
 * <p>Returns {@code {intent_id, state, quote:{currency, total_minor, lines:[{item_id, quantity, unit_price_minor}]}}}
 * for AWAITING_APPROVAL, and {@code {intent_id, state, failure_reason?}} otherwise; after a P9 denial also
 * {@code reason}, {@code quoted} and {@code budget} (6.2), and after a refused link ({@code link_not_allowed}, 26.7)
 * also a fixed {@code reason}. It never returns the session id, merchant messages, the approval id or token, the
 * snapshot hash, or a link or its host (I6, I8, 9.2).
 */
final class ProposePurchaseTool implements Tool {

    static final String NAME = "propose_purchase";
    static final int MAX_LINES = 5;
    static final int MAX_QUANTITY = 5;
    static final int MAX_RATIONALE = 300;
    private static final ToolSpec SPEC = new ToolSpec(NAME,
            "Proposes a purchase for the user to approve: creates the purchase intent and gets the merchant's quote. "
                    + "It does not buy; the user approves separately.",
            CanonicalJson.write(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "merchant_id", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,100}$",
                                    "description", "The merchant_id from search_catalog."),
                            "items", Map.of("type", "array", "minItems", 1, "maxItems", MAX_LINES,
                                    "items", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "item_id", Map.of("type", "string",
                                                            "pattern", Args.ITEM_ID_SCHEMA),
                                                    "quantity", Map.of("type", "integer", "minimum", 1,
                                                            "maximum", MAX_QUANTITY)),
                                            "required", List.of("item_id", "quantity"),
                                            "additionalProperties", false)),
                            "rationale", Map.of("type", "string", "maxLength", MAX_RATIONALE,
                                    "description", "Why these items match the user's request.")),
                    "required", List.of("merchant_id", "items", "rationale"),
                    "additionalProperties", false)));

    private final ToolPorts ports;

    ProposePurchaseTool(ToolPorts ports) {
        this.ports = Objects.requireNonNull(ports, "ports");
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    /** The failure_reason of an offer whose link I12 refused (7.2 link_refused, 26.7). */
    private static final String LINK_NOT_ALLOWED = "link_not_allowed";
    /** 6.2: a fixed text of ours, never the link or its host. */
    static final String LINK_NOT_ALLOWED_REASON = "the store's link is not on the seller's own site";
    /** The failure_reason of a quote P9 refused (9.1). */
    private static final String OVER_BUDGET = "policy_P9";

    @Override
    public ToolOutcome run(ToolContext context, Map<String, Object> arguments) {
        MerchantId merchant;
        List<ProposedLine> lines = new ArrayList<>();
        String rationale;
        try {
            Args.only(arguments, Set.of("merchant_id", "items", "rationale"));
            merchant = new MerchantId(Args.id(arguments, "merchant_id"));
            for (Object entry : Args.list(arguments, "items", 1, MAX_LINES)) {
                Map<String, Object> item = Args.object(entry, "each of items");
                Args.only(item, Set.of("item_id", "quantity"));
                lines.add(new ProposedLine(Args.itemId(item.get("item_id"), "item_id"),
                        Args.integer(item.get("quantity"), "quantity", 1, MAX_QUANTITY)));
            }
            rationale = Args.truncated(arguments, "rationale", MAX_RATIONALE);
        } catch (Args.Invalid e) {
            return ToolOutcome.invalid(e.getMessage());
        }
        // The budget is the run's, from its context: no argument can set, raise or clear it (6.2, P9).
        ProposalResult proposal = ports.orchestrator().propose(context.auth(), merchant, lines, rationale,
                context.budget());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent_id", proposal.intentId().value());
        result.put("state", proposal.state().name());
        if (proposal.state() == IntentState.AWAITING_APPROVAL && proposal.quote().isPresent()) {
            result.put("quote", quote(proposal.quote().get()));
        } else {
            IntentStatus status = ports.orchestrator().status(context.auth(), proposal.intentId());
            status.failureReason().ifPresent(r -> result.put("failure_reason", ToolText.failureReason(r)));
            if (status.failureReason().filter(LINK_NOT_ALLOWED::equals).isPresent()) {
                result.put("reason", LINK_NOT_ALLOWED_REASON);
            }
            if (status.failureReason().filter(OVER_BUDGET::equals).isPresent() && context.budget().isPresent()
                    && proposal.quote().isPresent()) {
                // Our own text and numbers only (6.2), so the agent can try a cheaper item or say none fits.
                result.put("reason", "over your budget of " + context.budget().get().display() + " (P9)");
                result.put("quoted", Map.of("currency", proposal.quote().get().currency(),
                        "total_minor", proposal.quote().get().totalMinor()));
                result.put("budget", Map.of("amount_minor", context.budget().get().amountMinor(),
                        "currency", context.budget().get().currency()));
            }
        }
        return new ToolOutcome.Result(CanonicalJson.write(result), "ok", Optional.of(proposal.intentId()));
    }

    private static Map<String, Object> quote(CheckoutView view) {
        List<Object> lines = new ArrayList<>();
        for (LineView line : view.lines()) {
            lines.add(Map.of(
                    "item_id", ToolText.merchantId("item_id", line.itemId()),
                    "quantity", line.quantity(),
                    "unit_price_minor", line.unitPriceMinor()));
        }
        return Map.of(
                "currency", view.currency(),
                "total_minor", view.totalMinor(),
                "lines", lines);
    }
}
