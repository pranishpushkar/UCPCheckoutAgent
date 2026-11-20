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
import dev.ucplab.core.merchant.Availability;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.Money;
import dev.ucplab.core.merchant.VariantOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 6.3 [CONTRACT] earlier turns in the context, end to end: the live re-check from India, where the second turn's model
 * calls each resent the first turn's catalog payloads, grew to 33,536 input tokens and timed out. The scripted model here
 * reports one input token per four characters it is sent, as a real model's count grows with the text.
 */
class ContinuationContextTest {

    private static final RunId RUN = new RunId("run-cheaper");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);

    /** Ten shoes with long titles, sellers and options, or one plain one: the same search, a big and a small payload. */
    private static CatalogGateway catalog(boolean big) {
        List<CatalogProduct> products = IntStream.range(0, big ? 10 : 1).mapToObj(i -> new CatalogProduct(
                "gid://shopify/p/FAKEUPID00000" + i, big ? "Shoe " + i + " " + "T".repeat(115) : "Shoe",
                Optional.empty(), List.of(new CatalogVariant("gid://shopify/ProductVariant/100000" + i,
                        big ? "Shoe " + i + " " + "T".repeat(115) : "Shoe", new Money(1_200_000, "INR"),
                        new Availability(true, Optional.empty()), Optional.of(big ? "S".repeat(60) : "S"),
                        big ? IntStream.range(0, 4).mapToObj(o -> new VariantOption("N".repeat(40), "L".repeat(40)))
                                .toList() : List.of())))).toList();
        return new CatalogGateway() {
            @Override
            public CallOutcome<CatalogPage> search(CatalogSearch request) {
                return new CallOutcome.Ok<>(200, new CatalogPage(products, false), List.of());
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
    }

    /** What the model is sent, in characters: the prompt, every message's text, call and result. */
    private static long size(Conversation c) {
        long n = c.systemPrompt().length();
        for (Message m : c.messages()) {
            n += switch (m) {
                case Message.User u -> u.text().length();
                case Message.Model t -> t.text().length() + t.toolCalls().toString().length();
                case Message.ToolResult r -> r.json().length();
            };
        }
        return n;
    }

    /** One run of two turns; the sizes of every model call, in order, and the turns' input tokens as recorded. */
    private record Run(List<Long> sizes, List<String> secondTurnResults, long firstTurnTokens, long secondTurnTokens) {}

    /**
     * Turn 1: a search, then a reply. Turn 2 ("cheaper please"): a search of its own, then a reply. The model's input
     * token count is a quarter of what it was sent.
     */
    private static Run twoTurns(boolean big) {
        List<Long> sizes = new ArrayList<>();
        List<String> secondTurnResults = new ArrayList<>();
        ModelClient model = (c, tools) -> {
            long n = size(c);
            sizes.add(n);
            Message last = c.messages().getLast();
            Usage usage = new Usage(n / 4, 10);
            if (last instanceof Message.User) {
                return new ModelTurn("", List.of(new ToolCall("s" + sizes.size(), "search_catalog",
                        Map.of("query", "red nike shoes", "max_results", 10L))), usage);
            }
            if (sizes.size() > 2) {
                c.messages().stream().filter(m -> m instanceof Message.ToolResult)
                        .map(m -> ((Message.ToolResult) m).json()).forEach(secondTurnResults::add);
            }
            return new ModelTurn("Here are some shoes.", List.of(), usage);
        };
        InMemoryRunRepository runs = new InMemoryRunRepository(CLOCK);
        AgentRuntime runtime = new AgentRuntime(model, "scripted",
                Stubs.ports(catalog(big), new Stubs.RecordingOrchestrator()), runs, AgentBudget.DEFAULT,
                Duration.ofMinutes(5), CLOCK);
        RunSession first = runtime.start(Stubs.ALICE, RUN, "red nike shoes under 15000 rupees",
                (q, t) -> Optional.empty(), new RunTrace(RUN));
        long afterFirst = runs.find(Stubs.ALICE.user(), RUN).orElseThrow().inputTokens();
        runtime.resume(Stubs.ALICE, RUN, first.conversation(), "cheaper please", (q, t) -> Optional.empty(),
                new RunTrace(RUN));
        long afterSecond = runs.find(Stubs.ALICE.user(), RUN).orElseThrow().inputTokens();
        return new Run(sizes, secondTurnResults, afterFirst, afterSecond - afterFirst);
    }

    // Covers: 6.3 earlier turns, 6.3 tokens (turn 2's input tokens do not grow with turn 1's catalog payloads: the first
    // turn's big and small searches differ by thousands of characters, and turn 2's calls by at most one digit, the
    // count in "searched: 10 results")
    @Test
    void theSecondTurnsInputTokens_doNotGrowWithTheFirstTurnsCatalogPayloads() {
        Run big = twoTurns(true);
        Run small = twoTurns(false);

        assertThat(big.sizes()).hasSize(4);
        long payload = big.sizes().get(1) - small.sizes().get(1);
        assertThat(payload).as("one search's big payload over its small one, as turn 1 sees it whole").isGreaterThan(3_000);
        assertThat(big.sizes().get(2) - small.sizes().get(2)).as("turn 2's first call: turn 1's result summarised")
                .isBetween(0L, 1L);
        // Resent whole, turn 1's payload would add itself to both of turn 2's calls: three payloads in all, with turn
        // 2's own search. Summarised, turn 2 pays for its own search once, plus at most a digit and the rounding.
        assertThat(big.secondTurnTokens() - small.secondTurnTokens())
                .as("turn 2's input tokens: only its own search's payload differs, never turn 1's")
                .isLessThanOrEqualTo(payload / 4 + 2);
    }

    // Covers: 6.3 earlier turns (the current turn keeps its own tool results whole: turn 2's reply call sees turn 2's
    // search in full, with its items and prices, and turn 1's only as a summary)
    @Test
    void theCurrentTurn_keepsItsOwnResultsWhole() {
        Run run = twoTurns(true);

        assertThat(run.secondTurnResults()).hasSize(2);
        assertThat(run.secondTurnResults().getFirst()).isEqualTo("{\"earlier\":\"searched: 10 results\"}");
        assertThat(run.secondTurnResults().getLast()).startsWith("{\"items\":[").contains("\"price_minor\":1200000")
                .contains("T".repeat(100));
    }
}
