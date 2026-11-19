package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.InMemoryMerchantRegistry;
import dev.ucplab.core.merchant.MerchantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 6.2, 26.7: {@code propose_purchase} at a link merchant. When the offer's link is refused the result carries a fixed
 * text of ours as {@code reason}, and never the link or its host (I6, I8).
 */
class ProposeLinkReasonTest {

    static final IntentId ID = new IntentId("intent-1");

    /** An orchestrator whose every proposal ends FAILED_NOT_CHARGED with {@code reason}. */
    static Orchestrator failingWith(String reason) {
        return new Orchestrator() {
            @Override
            public ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines,
                    String rationale, Optional<Budget> budget) {
                return new ProposalResult(ID, IntentState.FAILED_NOT_CHARGED, Optional.empty());
            }

            @Override
            public StepResult advance(IntentId id) {
                throw new AssertionError("the tool does not advance");
            }

            @Override
            public IntentStatus status(AuthContext auth, IntentId id) {
                return new IntentStatus(id, IntentState.FAILED_NOT_CHARGED, Optional.empty(), Optional.of(reason));
            }
        };
    }

    static Map<String, Object> propose(String failure) {
        ToolPorts ports = new ToolPorts(new InMemoryMerchantRegistry(List.of()), Map.of(), failingWith(failure),
                ToolPorts.DEFAULT_PER_MERCHANT);
        AuthContext auth = new AuthContext(new UserId("alice"), "alice@example.com", Instant.parse("2026-11-12T10:00:00Z"),
                "session-alice");
        ToolOutcome outcome = new ProposePurchaseTool(ports).run(new ToolContext(auth, new RunId("run-1"),
                Optional.of(new Budget(50_000, "USD"))), Map.of("merchant_id", "shopify-catalog",
                "items", List.of(Map.of("item_id", "gid://shopify/ProductVariant/1000001", "quantity", 1L)),
                "rationale", "a board"));
        assertThat(outcome).isInstanceOf(ToolOutcome.Result.class);
        return CanonicalJson.readObject(((ToolOutcome.Result) outcome).json());
    }

    // Covers: 6.2, 26.7, I12 C3, I6 (a refused link: failure_reason link_not_allowed and the fixed reason; no host)
    @Test
    void aRefusedLink_carriesTheFixedReason() {
        Map<String, Object> result = propose("link_not_allowed");

        assertThat(result).containsEntry("failure_reason", "link_not_allowed")
                .containsEntry("reason", "the store's link is not on the seller's own site");
        assertThat(CanonicalJson.write(result)).doesNotContain("http").doesNotContain(".example");
    }

    // Covers: 6.2, 26.7 (the other link failures carry their code and no reason)
    @Test
    void otherLinkFailures_carryNoReason() {
        for (String failure : List.of("link_quantity_unsupported", "catalog_unreadable", "item_unavailable")) {
            assertThat(propose(failure)).as(failure).containsEntry("failure_reason", failure).doesNotContainKey("reason");
        }
    }
}
