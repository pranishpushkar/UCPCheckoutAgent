package dev.ucplab.eval;

import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.faultproxy.RuleSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * One row of the scripted catalogue (13.2) as the harness runs it in M4: the fault (proxy rules built for the run's time
 * scale), what the auto-approver does, how the proposal is driven, and what must come out.
 *
 * @param target the merchant the proposal names; only {@link Scenarios#SHOP} is in a lane's registry
 * @param paymentToken the mock card token the agent pays with ({@code tok_decline} makes the merchant decline)
 */
public record Scenario(
        String id,
        String title,
        String fault,
        Set<IntentState> expected,
        List<String> requiredInvariants,
        Function<TimeScale, RuleSet> rules,
        AutoApprover.Policy approver,
        Driver driver,
        MerchantId target,
        List<ProposedLine> basket,
        String paymentToken) {

    /** How the harness calls {@code propose}. */
    public enum Driver {
        /** Once. */
        PROPOSE_ONCE,
        /** Twice with the same basket and user: one after the other in even runs, two threads at once in odd runs. */
        PROPOSE_TWICE
    }

    public Scenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(fault, "fault");
        expected = Set.copyOf(expected);
        requiredInvariants = List.copyOf(requiredInvariants);
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(approver, "approver");
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(target, "target");
        basket = List.copyOf(basket);
        Objects.requireNonNull(paymentToken, "paymentToken");
        if (expected.isEmpty() || expected.stream().anyMatch(s -> !s.isTerminal())) {
            throw new IllegalArgumentException(id + ": expected outcomes must be terminal states");
        }
    }
}
