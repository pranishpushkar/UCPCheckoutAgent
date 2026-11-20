package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.InMemoryRunRepository;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.BuyerCountry;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.merchant.Availability;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 6.4 v9, 23.1: the live re-run's follow-up. The first turn proposes under a budget of ₹15,000, so the intent copies
 * it; the owner changes the run's budget to ₹8,000; the user sends "cheaper please". Whatever the model does, the
 * follow-up's search carries the run's changed budget, in code, never the intent's.
 */
class FollowUpBudgetTest {

    private static final RunId RUN = new RunId("run-cheaper");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private static final Budget FIRST = new Budget(1_500_000, "INR");
    private static final Budget CHANGED = new Budget(800_000, "INR");

    // Covers: 6.4 v9, 6.2, 23.1 PATCH budget, 23.1 continuation, P9 (a follow-up after the owner's budget change
    // searches with the changed budget, ₹8,000, while the first turn's intent keeps the ₹15,000 it copied; the follow-up's
    // [BUDGET] paragraph names the changed one)
    @Test
    void aFollowUpAfterABudgetChange_searchesWithTheChangedBudget_notTheIntents() {
        List<CatalogSearch> asked = new CopyOnWriteArrayList<>();
        CatalogProduct shoe = new CatalogProduct("p1", "Shoe", Optional.empty(), List.of(new CatalogVariant("v1",
                "Shoe", new Money(1_200_000, "INR"), new Availability(true, Optional.empty()), Optional.of("Seller"),
                List.of())));
        CatalogGateway catalog = new CatalogGateway() {
            @Override
            public CallOutcome<CatalogPage> search(CatalogSearch request) {
                asked.add(request);
                return new CallOutcome.Ok<>(200, new CatalogPage(List.of(shoe), false), List.of());
            }

            @Override
            public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CallOutcome<CatalogProduct> product(String id) {
                throw new UnsupportedOperationException();
            }
        };
        List<Conversation> seen = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (c, tools) -> {
            seen.add(c);
            Usage usage = new Usage(10, 10);
            return switch (calls.incrementAndGet()) {
                case 1, 4 -> new ModelTurn("", List.of(new ToolCall("s" + calls.get(), "search_catalog",
                        Map.of("query", "red nike shoes", "max_results", 5L))), usage);
                case 2 -> new ModelTurn("", List.of(new ToolCall("p2", "propose_purchase", Map.of("merchant_id",
                        Stubs.SHOP.value(), "items", List.of(Map.of("item_id", "v1", "quantity", 1L)),
                        "rationale", "Red Nike shoes."))), usage);
                default -> new ModelTurn("Done.", List.of(), usage);
            };
        };
        Stubs.RecordingOrchestrator orchestrator = new Stubs.RecordingOrchestrator();
        orchestrator.proposal = new ProposalResult(new IntentId("intent-1"), IntentState.AWAITING_APPROVAL,
                Optional.empty());
        InMemoryRunRepository runs = new InMemoryRunRepository(CLOCK);
        AgentRuntime runtime = new AgentRuntime(model, "scripted", Stubs.ports(catalog, orchestrator), runs,
                AgentBudget.DEFAULT, Duration.ofMinutes(5), CLOCK);

        RunSession first = runtime.start(Stubs.ALICE, RUN, "red nike shoes under 15000 rupees",
                (q, t) -> Optional.empty(), new RunTrace(RUN), Optional.of(FIRST), new BuyerCountry("IN"));
        assertThat(runs.setBudget(Stubs.ALICE.user(), RUN, Optional.of(CHANGED))).isTrue();
        runtime.resume(Stubs.ALICE, RUN, first.conversation(), "cheaper please", (q, t) -> Optional.empty(),
                new RunTrace(RUN));

        // The third is 6.2's near miss: nothing at ₹12,000 fits ₹8,000, so the same search is asked once without a filter.
        assertThat(asked).extracting(CatalogSearch::maxPrice).containsExactly(
                Optional.of(new Money(1_500_000, "INR")), Optional.of(new Money(800_000, "INR")), Optional.empty());
        assertThat(orchestrator.budgets).containsExactly(Optional.of(FIRST));
        assertThat(seen.get(3).messages().getLast()).isEqualTo(new Message.User("cheaper please"));
        assertThat(seen.get(3).systemPrompt()).contains("[BUDGET] The user's budget for this run is ₹8,000.00.")
                .doesNotContain("₹15,000.00");
    }
}
