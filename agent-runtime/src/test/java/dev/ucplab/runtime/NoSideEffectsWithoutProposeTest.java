package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.agent.InMemoryRunRepository;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.faultproxy.LogEntry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 6.3: a run that ends without {@code propose_purchase} has no side effects. Each case runs the loop against the
 * reference merchant through the fault proxy, reading the catalog (search, product, an ask), and then ends by one of
 * the four budget limits, by a Done with no proposal, or by an ask_user timeout. None may leave an intent or an
 * approval for the user, a request other than discovery and catalog reads in the proxy log (no
 * {@code /checkout-sessions}), or an order at the merchant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoSideEffectsWithoutProposeTest {

    private static final String SEARCH = "{\"tool\":\"search_catalog\",\"args\":{\"query\":\"mug\",\"max_results\":3}}";
    private static final String PRODUCT = "{\"tool\":\"get_product\",\"args\":{\"item_id\":\"item_456\"}}";
    private static final String ASK = "{\"tool\":\"ask_user\",\"args\":{\"question\":\"Which colour?\"}}";
    /** Discovery and the three catalog reads: everything else would be a side effect. */
    private static final Pattern READS = Pattern.compile(
            "GET /\\.well-known/ucp|POST (/[A-Za-z0-9_./-]*)?/catalog/(search|product|lookup)");

    private RuntimeLab lab;

    @BeforeAll
    void start() throws Exception {
        lab = new RuntimeLab();
    }

    @AfterAll
    void stop() {
        lab.close();
    }

    private static ScriptedModel script(String... turns) {
        return ScriptedModel.parse("{\"name\":\"no-side-effects\",\"turns\":[" + String.join(",", turns) + "]}");
    }

    private static String turn(String... calls) {
        return "{\"call\":[" + String.join(",", calls) + "]}";
    }

    /** Runs one case for a fresh user and checks the merchant, the proxy log and the repositories afterwards. */
    private RunOutcome runCase(String user, ModelClient model, Clock clock, UserChannel channel) {
        AuthContext auth = lab.auth(user);
        int ordersBefore = lab.merchant.orders().size();
        int approvalsOpened = lab.opened.size();
        long logFrom = lab.settledLog().stream().mapToLong(LogEntry::seq).max().orElse(0);
        AgentRuntime runtime = new AgentRuntime(model, "scripted", lab.ports(), new InMemoryRunRepository(),
                AgentBudget.DEFAULT, Duration.ofMinutes(5), clock);

        RunOutcome outcome = runtime.run(auth, new RunId("run-" + user), "I want a mug.", channel);

        List<LogEntry> log = lab.settledLog().stream().filter(e -> e.seq() > logFrom).toList();
        assertThat(log).as(user + ": the run read the catalog").isNotEmpty();
        assertThat(log).as(user + ": only discovery and catalog reads")
                .allSatisfy(e -> assertThat(e.method() + " " + e.path()).matches(READS));
        assertThat(log).as(user + ": no checkout session").noneSatisfy(e -> assertThat(e.path())
                .contains("checkout-sessions"));
        assertThat(lab.intentsOf(auth.user())).as(user + ": no intent").isEmpty();
        assertThat(lab.approvalsOf(auth.user())).as(user + ": no approval").isEmpty();
        assertThat(lab.opened).as(user + ": no approval opened").hasSize(approvalsOpened);
        assertThat(lab.merchant.orders()).as(user + ": /_lab/orders unchanged").hasSize(ordersBefore);
        System.out.println("NoSideEffectsWithoutProposeTest " + user + ": " + outcome.getClass().getSimpleName()
                + (outcome instanceof RunOutcome.BudgetExceeded b ? " " + b.which() : "") + ", " + log.size()
                + " proxy entries, all reads");
        return outcome;
    }

    // Covers: 6.3 (the model-call limit after catalog reads and an answered question: no side effect)
    @Test
    void modelCallLimit_noSideEffects() {
        String[] turns = new String[7];
        turns[0] = turn(SEARCH);
        turns[1] = turn(PRODUCT);
        turns[2] = turn(ASK);
        for (int i = 3; i < 7; i++) {
            turns[i] = turn(SEARCH);
        }

        RunOutcome outcome = runCase("model-calls", script(turns), lab.clock, (q, t) -> Optional.of("blue"));

        assertThat(outcome).isEqualTo(new RunOutcome.BudgetExceeded(BudgetLimit.MODEL_CALLS, List.of()));
    }

    // Covers: 6.3 (the tool-call limit: nine reads asked for, eight run, no side effect)
    @Test
    void toolCallLimit_noSideEffects() {
        String[] nine = Collections.nCopies(8, SEARCH).toArray(new String[0]);
        String calls = String.join(",", nine) + "," + PRODUCT;

        RunOutcome outcome = runCase("tool-calls", script("{\"call\":[" + calls + "]}"), lab.clock,
                (q, t) -> Optional.empty());

        assertThat(outcome).isEqualTo(new RunOutcome.BudgetExceeded(BudgetLimit.TOOL_CALLS, List.of()));
    }

    // Covers: 6.3 (the wall-clock limit: past 30 s on the run's clock after the first turn's reads, no side effect)
    @Test
    void wallClockLimit_noSideEffects() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ScriptedModel scripted = script(turn(SEARCH, PRODUCT), turn(SEARCH), "{\"text\":\"never\"}");
        AtomicInteger calls = new AtomicInteger();
        ModelClient slowSecondCall = (conversation, tools) -> {
            ModelTurn turn = scripted.generate(conversation, tools);
            if (calls.incrementAndGet() == 2) {
                clock.advance(Duration.ofSeconds(31));
            }
            return turn;
        };

        RunOutcome outcome = runCase("wall-clock", slowSecondCall, clock, (q, t) -> Optional.empty());

        assertThat(outcome).isEqualTo(new RunOutcome.BudgetExceeded(BudgetLimit.WALL_CLOCK, List.of()));
    }

    // Covers: 6.3 (the token ceiling: one turn overshoots after a read, the next call is refused, no side effect)
    @Test
    void tokenCeiling_noSideEffects() {
        ScriptedModel model = script(turn(SEARCH),
                "{\"call\":[" + PRODUCT + "],\"usage\":{\"inputTokens\":19000,\"outputTokens\":1500}}",
                "{\"text\":\"never\"}");

        RunOutcome outcome = runCase("tokens", model, lab.clock, (q, t) -> Optional.empty());

        assertThat(outcome).isEqualTo(new RunOutcome.BudgetExceeded(BudgetLimit.TOKENS, List.of()));
    }

    // Covers: 6.3, FR3 (Done with no proposal after reads and a question: no side effect)
    @Test
    void doneWithoutPropose_noSideEffects() {
        ScriptedModel model = script(turn(SEARCH), turn(PRODUCT), turn(ASK), "{\"text\":\"Here are some mugs.\"}");

        RunOutcome outcome = runCase("done", model, lab.clock, (q, t) -> Optional.of("any"));

        assertThat(outcome).isEqualTo(new RunOutcome.Done("Here are some mugs.", List.of()));
    }

    // Covers: 6.2, 6.3 (ask_user times out after a read: UserTimedOut, no further call, no side effect)
    @Test
    void userTimedOut_noSideEffects() {
        ScriptedModel model = script(turn(SEARCH), turn(ASK, PRODUCT), "{\"text\":\"never\"}");

        RunOutcome outcome = runCase("user-timeout", model, lab.clock, (q, t) -> Optional.empty());

        assertThat(outcome).isEqualTo(new RunOutcome.UserTimedOut("Which colour?"));
    }
}
