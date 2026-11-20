package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.InMemoryRunRepository;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.core.intent.BuyerCountry;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.Availability;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Money;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.core.merchant.VariantOption;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 6.3 [CONTRACT] superseded search results within a run, replayed on the live re-run from India that stopped at 20,230
 * tokens: "shoes under 15000", a search, an {@code ask_user} answered "uk 7", a second search, a {@code get_product},
 * then a proposal and the reply. The model here reports, for each call, the measured live size of the prompt, tool
 * declarations and first message (call 1's 1,484 input tokens) plus one token per four characters of every later message
 * it is sent, and the live run's output tokens; the catalog's items are the live run's size (see
 * {@link #theFixture_isTheLiveRunsSize}).
 */
class SupersededSearchTest {

    private static final RunId RUN = new RunId("run-uk7");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private static final MerchantId CATALOG = new MerchantId("shopify-catalog");
    /** Call 1's input tokens on the live run: the system prompt, the tool declarations and "shoes under 15000". */
    private static final long PROMPT_TOKENS = 1_484;
    /** The live run's output tokens per call; the sixth, the reply after the proposal, did not happen there. */
    private static final long[] OUTPUT = {175, 196, 105, 452, 157, 150};

    private static final List<Conversation> SENT = new ArrayList<>();
    private static final List<String> READ = new ArrayList<>();
    private static RunSession session;
    private static RunRecord record;
    private static Stubs.RecordingOrchestrator orchestrator;

    /** A Global Catalog shoe as the live run listed them: a long title, the seller, size and colour. */
    private static CatalogProduct shoe(String prefix, int i) {
        String title = "Nike Dunk Low Retro Men's Basketball Sneakers DD1391-1" + prefix + i + " White Black Leather";
        return new CatalogProduct("gid://shopify/p/UPID" + prefix + "0000" + i, title,
                Optional.of("Classic leather upper, rubber outsole and padded collar."),
                List.of(new CatalogVariant("gid://shopify/ProductVariant/" + prefix + "0000" + i, title,
                        new Money(1_150_000 + 1_000L * i, "INR"), new Availability(true, Optional.empty()),
                        Optional.of("KICKS CREW"), List.of(new VariantOption("Size", "UK 7"),
                                new VariantOption("Colour", "White/Black")))));
    }

    /** Ten shoes for the first search and ten other ones for "shoes uk 7"; get_product finds any of them. */
    static CatalogGateway catalog() {
        List<CatalogProduct> first = IntStream.range(0, 10).mapToObj(i -> shoe("1", i)).toList();
        List<CatalogProduct> second = IntStream.range(0, 10).mapToObj(i -> shoe("2", i)).toList();
        return new CatalogGateway() {
            @Override
            public CallOutcome<CatalogPage> search(CatalogSearch request) {
                List<CatalogProduct> all = request.query().contains("7") ? second : first;
                return new CallOutcome.Ok<>(200, new CatalogPage(all.subList(0, Math.min(request.limit(), all.size())),
                        false), List.of());
            }

            @Override
            public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CallOutcome<CatalogProduct> product(String id) {
                return first.stream().filter(p -> p.variants().getFirst().itemId().equals(id)).findFirst()
                        .or(() -> second.stream().filter(p -> p.variants().getFirst().itemId().equals(id)).findFirst())
                        .<CallOutcome<CatalogProduct>>map(p -> new CallOutcome.Ok<>(200, p, List.of()))
                        .orElseGet(() -> new CallOutcome.ProtocolError<>(404, "not_found", "no such item",
                                Optional.empty()));
            }
        };
    }

    static ToolPorts ports(Transport transport, Stubs.RecordingOrchestrator orchestrator) {
        MerchantEntry entry = transport == Transport.CATALOG
                ? new MerchantEntry(CATALOG, URI.create("https://catalog.shopify.com/api/ucp/mcp"), "Shopify catalog",
                        MerchantStatus.ACTIVE, Transport.CATALOG, Completion.LINK, false, Optional.empty())
                : new MerchantEntry(CATALOG, URI.create("http://127.0.0.1:1"), "Store", MerchantStatus.ACTIVE);
        return new ToolPorts(Stubs.registry(List.of(entry)), Map.of(CATALOG, catalog()), orchestrator);
    }

    /** What the model is sent after the first user message, in characters: every message's text, call and result. */
    static long size(List<Message> messages) {
        long n = 0;
        for (Message m : messages.subList(1, messages.size())) {
            n += switch (m) {
                case Message.User u -> u.text().length();
                case Message.Model t -> t.text().length() + t.toolCalls().toString().length();
                case Message.ToolResult r -> r.json().length();
            };
        }
        return n;
    }

    /** The first item_id the model can read for an earlier search: from its summary, else from its whole result. */
    private static String earlierItem(Conversation c) {
        Message.ToolResult first = c.messages().stream().filter(m -> m instanceof Message.ToolResult r
                && r.tool().equals("search_catalog")).map(Message.ToolResult.class::cast).findFirst().orElseThrow();
        Map<String, Object> json = CanonicalJson.readObject(first.json());
        if (json.get("item_ids") instanceof List<?> ids) {
            return (String) ids.getFirst();
        }
        return (String) ((Map<?, ?>) ((List<?>) json.get("items")).getFirst()).get("item_id");
    }

    @BeforeAll
    static void replay() {
        ModelClient model = (c, tools) -> {
            SENT.add(c);
            int n = SENT.size();
            Usage usage = new Usage(PROMPT_TOKENS + size(c.messages()) / 4, OUTPUT[Math.min(n, OUTPUT.length) - 1]);
            List<ToolCall> call = switch (n) {
                case 1 -> List.of(new ToolCall("c1", "search_catalog", Map.of("query", "shoes", "max_results", 10L)));
                case 2 -> List.of(new ToolCall("c2", "ask_user", Map.of("question", "Which size do you wear?")));
                case 3 -> List.of(new ToolCall("c3", "search_catalog",
                        Map.of("query", "shoes uk 7", "max_results", 10L)));
                case 4 -> {
                    READ.add(earlierItem(c));
                    yield List.of(new ToolCall("c4", "get_product", Map.of("item_id", READ.getFirst())));
                }
                case 5 -> List.of(new ToolCall("c5", "propose_purchase", Map.of("merchant_id", CATALOG.value(),
                        "items", List.of(Map.of("item_id", READ.getFirst(), "quantity", 1L)),
                        "rationale", "UK 7, white and black, under 15000 rupees")));
                default -> List.of();
            };
            return new ModelTurn(call.isEmpty() ? "I've proposed the Nike Dunk Low in UK 7." : "", call, usage);
        };
        orchestrator = new Stubs.RecordingOrchestrator();
        orchestrator.proposal = new ProposalResult(new IntentId("intent-1"), IntentState.AWAITING_APPROVAL,
                Optional.empty());
        InMemoryRunRepository runs = new InMemoryRunRepository(CLOCK);
        AgentRuntime runtime = new AgentRuntime(model, "scripted", ports(Transport.CATALOG, orchestrator), runs,
                AgentBudget.LIVE, Duration.ofMinutes(5), CLOCK);
        session = runtime.start(Stubs.ALICE, RUN, "shoes under 15000", (q, t) -> Optional.of("uk 7"),
                new RunTrace(RUN), Optional.empty(), new BuyerCountry("IN"));
        record = runs.find(Stubs.ALICE.user(), RUN).orElseThrow();
    }

    private static List<Message.ToolResult> results(Conversation c) {
        return c.messages().stream().filter(Message.ToolResult.class::isInstance).map(Message.ToolResult.class::cast)
                .toList();
    }

    // Covers: 6.3 superseded search results, 6.3 tokens, 6.2 at most 5 from a catalog source (the live re-run's shape,
    // search -> ask_user -> answer -> search -> get_product -> propose, ends in a proposal under the unchanged 20,000
    // ceiling with headroom; the total is pinned, so a later change that sends more shows here)
    @Test
    void theLiveRerunsShape_endsInAProposal_underTheCeiling_withHeadroom() {
        assertThat(session.outcome()).isInstanceOf(RunOutcome.Done.class);
        assertThat(orchestrator.calls).hasSize(1);
        assertThat(SENT).hasSize(6);
        long total = record.inputTokens() + record.outputTokens();
        assertThat(total).as("the run's tokens, under 20,000 with 3,000 to spare").isLessThanOrEqualTo(17_000);
        assertThat(total).as("pinned: a change that sends the model more shows here").isEqualTo(15_049);
    }

    // Covers: 6.3 superseded search results (a search is sent whole until it is superseded: call 2 reads the first search
    // whole; after the answer, calls 3 on read it as its summary and ids; the latest search, get_product and the ask_user
    // answer stay whole)
    @Test
    void aSupersededSearch_isSentAsItsSummaryAndIds_andTheRestStaysWhole() {
        assertThat(results(SENT.get(1)).getFirst().json()).startsWith("{\"items\":[");
        String summary = "{\"earlier\":\"searched: 5 results\",\"item_ids\":[\"gid://shopify/ProductVariant/100000\","
                + "\"gid://shopify/ProductVariant/100001\",\"gid://shopify/ProductVariant/100002\","
                + "\"gid://shopify/ProductVariant/100003\",\"gid://shopify/ProductVariant/100004\"]}";
        for (int call = 2; call < 6; call++) {
            assertThat(results(SENT.get(call)).getFirst().json()).as("call " + (call + 1)).isEqualTo(summary);
            assertThat(results(SENT.get(call)).get(1).json()).as("the answer, whole").contains("uk 7</untrusted>");
        }
        assertThat(results(SENT.get(3)).get(2).json()).as("the latest search, whole").startsWith("{\"items\":[")
                .contains("KICKS CREW");
        assertThat(results(SENT.get(4)).get(3).json()).as("get_product, whole").contains("\"description\"");
    }

    // Covers: 6.3 superseded search results (after supersession, get_product on an item_id from the earlier search's
    // summary, an item the latest search does not hold, still reads it, and the proposal is of that item)
    @Test
    void getProduct_onAnIdFromTheSummary_stillReadsIt() {
        assertThat(orchestrator.calls).as("the run reached its proposal").hasSize(1);
        assertThat(READ).containsExactly("gid://shopify/ProductVariant/100000");
        String read = results(SENT.get(4)).get(3).json();
        assertThat(read).contains("\"item_id\":\"gid://shopify/ProductVariant/100000\"")
                .contains("\"price_minor\":1150000").doesNotContain("\"error\"");
        assertThat(orchestrator.calls.getFirst()).contains("gid://shopify/ProductVariant/100000");
    }

    // Covers: 6.3 superseded search results, 6.4 L2, I6 (a summary holds no merchant text: no untrusted wrapper, title,
    // seller or option, only our count and the validated ids)
    @Test
    void aSummary_holdsNoMerchantText() {
        assertThat(SENT).as("the run reached its reply").hasSize(6);
        String summary = results(SENT.get(5)).getFirst().json();
        assertThat(CanonicalJson.readObject(summary)).containsOnlyKeys("earlier", "item_ids");
        assertThat(summary).doesNotContain("untrusted").doesNotContain("Nike").doesNotContain("KICKS")
                .doesNotContain("UK 7").doesNotContain("INR");
    }

    // Covers: 6.3 superseded search results (only what the model is sent changes: the run's stored conversation keeps
    // both searches whole)
    @Test
    void theStoredConversation_keepsEveryResultWhole() {
        List<Message.ToolResult> stored = results(session.conversation());
        assertThat(stored.getFirst().json()).startsWith("{\"items\":[").contains("KICKS CREW");
        assertThat(stored.get(2).json()).startsWith("{\"items\":[").contains("KICKS CREW");
    }

    // Covers: 6.3 superseded search results, 6.3 tokens (the fixture is the live run's size: one search of ten items at a
    // store, which gives all ten, is 1,429 to 1,889 tokens, as the live run's two searches measured)
    @Test
    void theFixture_isTheLiveRunsSize() {
        ToolRegistry tools = ToolRegistry.of(ports(Transport.REST, new Stubs.RecordingOrchestrator()),
                (q, t) -> Optional.empty(), Duration.ofMinutes(5));
        String json = tools.find("search_catalog").orElseThrow().run(new ToolContext(Stubs.ALICE, RUN,
                Optional.empty(), new BuyerCountry("IN")), Map.of("query", "shoes", "max_results", 10L)).json();
        assertThat(json.length() / 4).isBetween(1_429, 1_889);
    }
}
