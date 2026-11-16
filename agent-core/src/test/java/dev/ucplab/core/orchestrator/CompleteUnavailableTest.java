package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.core.merchant.UpdateCheckout;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 7.2 {@code complete_unavailable} (8.4, N1; H5 C6): at a merchant whose transport offers no complete ({@code transport:
 * mcp} with {@code completion: agent}, which startup refuses, 19), an approved intent ends FAILED_NOT_CHARGED with its
 * session cancelled. The row is checked before the write-ahead, so no complete attempt is written and nothing
 * reconciles. Without it, the gateway's second guard ({@code complete} returns NotSent, sending nothing) cycled
 * COMPLETING and RECONCILING three times and escalated an outcome that was known.
 */
class CompleteUnavailableTest {

    private final Lab lab = new Lab();

    /** An owned MCP store completed by the agent: the entry startup refuses (19), here to reach the row. */
    private static final MerchantEntry MCP_AGENT = new MerchantEntry(SHOP, URI.create("https://shop.example"), "Shop",
            MerchantStatus.ACTIVE, Transport.MCP, Completion.AGENT, true, Optional.of("shop.example"));

    /** The lab merchant, except that {@code complete} is the MCP gateway's second guard: NotSent, nothing sent. */
    private final AtomicInteger completes = new AtomicInteger();
    private final MerchantGateway noComplete = new MerchantGateway() {
        @Override
        public MerchantProfile discover(MerchantId merchant) {
            return lab.merchant.discover(merchant);
        }

        @Override
        public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
            return lab.merchant.create(request, key);
        }

        @Override
        public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
            return lab.merchant.update(id, request, key);
        }

        @Override
        public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
            completes.incrementAndGet();
            return new CallOutcome.NotSent<>("no complete over MCP");
        }

        @Override
        public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
            return lab.merchant.cancel(id, key);
        }

        @Override
        public CallOutcome<CheckoutView> get(SessionId id) {
            return lab.merchant.get(id);
        }
    };

    private List<IntentEvent> events(IntentId id, String type) {
        return lab.events(id).stream().filter(e -> e.type().equals(type)).toList();
    }

    // Covers: 7.2 complete_unavailable, 8.4, N1, I1 (no money moved and the outcome is known: FAILED_NOT_CHARGED with
    // failure_reason complete_unavailable, one cancel, no complete attempt row, the gateway's complete never asked, and
    // the intent never in RECONCILING)
    @Test
    void approvedAtAMerchantWithNoComplete_failsNotCharged_withoutACompleteAttempt() {
        lab.entries = id -> id.equals(SHOP) ? Optional.of(MCP_AGENT) : Optional.empty();
        Lab.Worker worker = lab.worker("w", noComplete);
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        lab.decide(worker, id, true);

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);

        assertThat(lab.intent(id).failureReason()).contains("complete_unavailable");
        assertThat(lab.intents.attempts(id)).noneMatch(a -> a.op() == AttemptOp.COMPLETE);
        assertThat(lab.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.CANCEL).hasSize(1);
        assertThat(completes).as("the gateway's complete is never asked").hasValue(0);
        assertThat(events(id, "transition")).noneMatch(e -> e.payloadJson().contains("RECONCILING"));
        assertThat(events(id, "transition")).extracting(e -> e.payloadJson()).contains(
                "{\"event\":\"COMPLETE_UNAVAILABLE\",\"from\":\"APPROVED\",\"guard\":\"NONE\",\"to\":\"FAILED_NOT_CHARGED\"}");
        assertThat(events(id, "complete_unavailable")).singleElement()
                .satisfies(e -> assertThat(e.payloadJson()).isEqualTo("{\"from\":\"APPROVED\"}"));
        assertThat(lab.merchant.orders()).isEmpty();
        assertThat(lab.merchant.session(lab.intent(id).sessionId().orElseThrow()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
    }

    // Covers: 7.2 (only a transport with no complete takes the row: an owned REST store completed by the agent still
    // sends complete and completes)
    @Test
    void approvedAtARestMerchant_stillCompletes() {
        MerchantEntry rest = new MerchantEntry(SHOP, URI.create("https://shop.example"), "Shop", MerchantStatus.ACTIVE,
                Transport.REST, Completion.AGENT, true, Optional.empty());
        lab.entries = id -> id.equals(SHOP) ? Optional.of(rest) : Optional.empty();
        Lab.Worker worker = lab.worker("w");
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        lab.decide(worker, id, true);

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.COMPLETED);
        assertThat(events(id, "complete_unavailable")).isEmpty();
    }
}
