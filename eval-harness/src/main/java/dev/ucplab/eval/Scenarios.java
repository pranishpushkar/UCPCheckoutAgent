package dev.ucplab.eval;

import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.faultproxy.Action;
import dev.ucplab.faultproxy.Match;
import dev.ucplab.faultproxy.Rule;
import dev.ucplab.faultproxy.RuleSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The M4 scripted scenarios (13.2) that the harness can run so far. */
public final class Scenarios {

    /** The one merchant in every lane's registry. */
    public static final MerchantId SHOP = new MerchantId("shop-lane");
    /** A merchant no lane's registry holds (S11). */
    public static final MerchantId UNLISTED = new MerchantId("shop-unlisted");
    /** Every M4 run holds exactly this one basket, so every order in a run belongs to that run (m4-plan section 5). */
    public static final List<ProposedLine> TWO_SHIRTS = List.of(new ProposedLine("item_123", 2));
    public static final String PAY_OK = "tok_ok";
    /** The mock card the merchant declines (A.2). */
    public static final String PAY_DECLINE = "tok_decline";
    /** S5: item_123's price after the change, up from 2500. */
    public static final long S5_NEW_PRICE = 2600;

    private static final Pattern ID = Pattern.compile("S(\\d+)([ab]?)");

    private Scenarios() {
    }

    public static final Scenario S0 = new Scenario("S0", "no fault", "none", Set.of(IntentState.COMPLETED),
            List.of("I1", "I2", "I3"), (t, run) -> RuleSet.empty(), AutoApprover.Policy.APPROVE_ALL,
            Scenario.Driver.PROPOSE_ONCE, SHOP, TWO_SHIRTS, PAY_OK);

    /** The create endpoint and the complete endpoint, as the proxy matches them. */
    static final String CREATE = "/ucp/v1/checkout-sessions";
    static final String COMPLETE = "/ucp/v1/checkout-sessions/*/complete";

    /**
     * S1 as reworded in 13.2 (D4): a listening server cannot refuse one connection, so the proxy closes it before
     * forwarding, which 8.1 classifies Uncertain as it does a refusal; create retries under the same key (8.2).
     */
    public static final Scenario S1 = new Scenario("S1", "connection closed before forwarding on the first 2 creates",
            "fail_before_forward {close} on POST " + CREATE + ", times 2", Set.of(IntentState.COMPLETED),
            List.of("I1", "I4"),
            (t, run) -> rules(run, new Rule("close-create", Match.of("POST", CREATE),
                    Action.FailBeforeForward.closeConnection()).withTimes(2)),
            AutoApprover.Policy.APPROVE_ALL, Scenario.Driver.PROPOSE_ONCE, SHOP, TWO_SHIRTS, PAY_OK);

    public static final Scenario S2 = new Scenario("S2", "complete response dropped after the merchant processed it",
            "forward_then_drop_response on POST " + COMPLETE + ", nth 1", Set.of(IntentState.COMPLETED),
            List.of("I1", "I4", "I7"),
            (t, run) -> rules(run, new Rule("drop-complete", Match.of("POST", COMPLETE).withNth(1),
                    new Action.ForwardThenDropResponse()).withTimes(1)),
            AutoApprover.Policy.APPROVE_ALL, Scenario.Driver.PROPOSE_ONCE, SHOP, TWO_SHIRTS, PAY_OK);

    /** S3: the merchant commits at once; the answer is held 1.5 complete timeouts, so the client times out first. */
    public static final Scenario S3 = new Scenario("S3", "complete response delayed past the client timeout",
            "forward_then_delay_response {1.5 x complete timeout} on POST " + COMPLETE + ", nth 1",
            Set.of(IntentState.COMPLETED), List.of("I1", "I7"),
            (t, run) -> rules(run, new Rule("hold-complete", Match.of("POST", COMPLETE).withNth(1),
                    new Action.ForwardThenDelayResponse(t.s3Hold().toMillis())).withTimes(1)),
            AutoApprover.Policy.APPROVE_ALL, Scenario.Driver.PROPOSE_ONCE, SHOP, TWO_SHIRTS, PAY_OK);

    public static final Scenario S4 = new Scenario("S4", "the same proposal submitted twice",
            "none; propose twice with the same basket (sequential in even runs, concurrent in odd runs)",
            Set.of(IntentState.COMPLETED), List.of("I1"), (t, run) -> RuleSet.empty(), AutoApprover.Policy.APPROVE_ALL,
            Scenario.Driver.PROPOSE_TWICE, SHOP, TWO_SHIRTS, PAY_OK);

    /**
     * S5 is reported as two rows (Decision 3). The price of item_123 goes from 2500 to 2600 between the quote and the
     * first complete, so complete rev 0 is answered {@code price_changed}, the agent re-quotes and asks again.
     */
    public static final Scenario S5A = new Scenario("S5a", "price changes between quote and complete; the re-quote is"
            + " approved", "POST /_lab/price item_123 2500 -> 2600 before the first decision; approve every quote",
            Set.of(IntentState.COMPLETED), List.of("I2", "I3"), (t, run) -> RuleSet.empty(),
            AutoApprover.Policy.APPROVE_ALL, Scenario.Driver.PROPOSE_ONCE, SHOP, TWO_SHIRTS, PAY_OK,
            m -> m.setPrice("item_123", S5_NEW_PRICE));

    public static final Scenario S5B = new Scenario("S5b", "price changes between quote and complete; the re-quote is"
            + " declined", "POST /_lab/price item_123 2500 -> 2600 before the first decision; decline the re-quote",
            Set.of(IntentState.REJECTED), List.of("I2", "I3"), (t, run) -> RuleSet.empty(),
            AutoApprover.Policy.APPROVE_FIRST_THEN_DECLINE, Scenario.Driver.PROPOSE_ONCE, SHOP, TWO_SHIRTS, PAY_OK,
            m -> m.setPrice("item_123", S5_NEW_PRICE));

    public static final Scenario S6 = new Scenario("S6", "item sells out after the quote",
            "POST /_lab/stock item_123 0 after the quote, before the first decision",
            Set.of(IntentState.FAILED_NOT_CHARGED), List.of("I1"), (t, run) -> RuleSet.empty(),
            AutoApprover.Policy.APPROVE_ALL, Scenario.Driver.PROPOSE_ONCE, SHOP, TWO_SHIRTS, PAY_OK,
            m -> m.setStock("item_123", 0));

    /** S7 (13.2: tok_decline). The report names no token, not even a mock one (I8), so the text describes the card. */
    public static final Scenario S7 = new Scenario("S7", "payment declined",
            "none; the agent pays with the mock card the merchant declines (A.2)",
            Set.of(IntentState.FAILED_NOT_CHARGED), List.of("I1"),
            (t, run) -> RuleSet.empty(), AutoApprover.Policy.APPROVE_ALL, Scenario.Driver.PROPOSE_ONCE, SHOP,
            TWO_SHIRTS, PAY_DECLINE);

    public static final Scenario S11 = new Scenario("S11", "merchant host not on the allowlist",
            "none; the proposal names a merchant missing from the registry", Set.of(IntentState.REJECTED),
            List.of("I3"), (t, run) -> RuleSet.empty(), AutoApprover.Policy.APPROVE_ALL, Scenario.Driver.PROPOSE_ONCE,
            UNLISTED, TWO_SHIRTS, PAY_OK);

    /** S13: both copies of the first complete reach the merchant, at once in even runs, one after the other in odd. */
    public static final Scenario S13 = new Scenario("S13", "duplicate_request on complete",
            "duplicate_request on POST " + COMPLETE + ", nth 1 (parallel in even runs, sequential in odd runs)",
            Set.of(IntentState.COMPLETED), List.of("I1"),
            (t, run) -> rules(run, new Rule("duplicate-complete", Match.of("POST", COMPLETE).withNth(1),
                    new Action.DuplicateRequest(run % 2 == 0 ? Action.DuplicateRequest.Mode.PARALLEL
                            : Action.DuplicateRequest.Mode.SEQUENTIAL)).withTimes(1)),
            AutoApprover.Policy.APPROVE_ALL, Scenario.Driver.PROPOSE_ONCE, SHOP, TWO_SHIRTS, PAY_OK);

    /** Every row the harness can run, in catalogue order. */
    public static List<Scenario> all() {
        return List.of(S0, S1, S2, S3, S4, S5A, S5B, S6, S7, S11, S13);
    }

    private static RuleSet rules(int run, Rule... rules) {
        return new RuleSet(run, List.of(rules));
    }

    /**
     * The index a scenario's run seeds are derived from ({@link EvalConfig#seed}): twice its catalogue number, plus one
     * for a "b" row, so seeds stay the same when rows are added (S0 is 0, S5a 10, S5b 11, S28 56).
     */
    public static int seedIndex(Scenario s) {
        Matcher m = ID.matcher(s.id());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a catalogue id: " + s.id());
        }
        return Integer.parseInt(m.group(1)) * 2 + ("b".equals(m.group(2)) ? 1 : 0);
    }

    public static Optional<Scenario> byId(String id) {
        return all().stream().filter(s -> s.id().equals(id)).findFirst();
    }
}
