package dev.ucplab.eval;

import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.MerchantId;
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

    private static final Pattern ID = Pattern.compile("S(\\d+)([ab]?)");

    private Scenarios() {
    }

    public static final Scenario S0 = new Scenario("S0", "no fault", "none", Set.of(IntentState.COMPLETED),
            List.of("I1", "I2", "I3"), t -> RuleSet.empty(), AutoApprover.Policy.APPROVE_ALL,
            Scenario.Driver.PROPOSE_ONCE, SHOP, TWO_SHIRTS, PAY_OK);

    public static final Scenario S4 = new Scenario("S4", "the same proposal submitted twice",
            "none; propose twice with the same basket (sequential in even runs, concurrent in odd runs)",
            Set.of(IntentState.COMPLETED), List.of("I1"), t -> RuleSet.empty(), AutoApprover.Policy.APPROVE_ALL,
            Scenario.Driver.PROPOSE_TWICE, SHOP, TWO_SHIRTS, PAY_OK);

    public static final Scenario S11 = new Scenario("S11", "merchant host not on the allowlist",
            "none; the proposal names a merchant missing from the registry", Set.of(IntentState.REJECTED),
            List.of("I3"), t -> RuleSet.empty(), AutoApprover.Policy.APPROVE_ALL, Scenario.Driver.PROPOSE_ONCE,
            UNLISTED, TWO_SHIRTS, PAY_OK);

    public static List<Scenario> all() {
        return List.of(S0, S4, S11);
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
