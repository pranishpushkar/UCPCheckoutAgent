package dev.ucplab.eval;

import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.faultproxy.RuleSet;
import dev.ucplab.merchant.LabMerchant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One row of the scripted catalogue (13.2) as the harness runs it in M4: the fault (proxy rules built for the run's time
 * scale), what the auto-approver does, how the proposal is driven, and what must come out.
 *
 * @param target the merchant the proposal names; only {@link Scenarios#SHOP} is in a lane's registry
 * @param paymentToken the mock card token the agent pays with ({@code tok_decline} makes the merchant decline)
 * @param beforeFirstDecision a change to the merchant made once the first quote is pending, just before the approver
 *     decides it (S5: the price, S6: the stock). Made before the decision rather than after it so the recovery loop
 *     cannot send the first complete ahead of it; the agent reads nothing from the merchant in between, so for the
 *     agent it is the same as a change after approval
 */
public record Scenario(
        String id,
        String title,
        String fault,
        Set<IntentState> expected,
        List<String> requiredInvariants,
        Rules rules,
        AutoApprover.Policy approver,
        Driver driver,
        MerchantId target,
        List<ProposedLine> basket,
        String paymentToken,
        MerchantEvent beforeFirstDecision) {

    /** The proxy rules of one run, built for the run's time scale; some rows vary by run (S13). */
    @FunctionalInterface
    public interface Rules {
        RuleSet build(TimeScale scale, int run);
    }

    /** A change the harness makes at the merchant, through its lab controls, during a run. */
    @FunctionalInterface
    public interface MerchantEvent {
        MerchantEvent NONE = m -> { };

        void apply(LabMerchant merchant);
    }

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
        Objects.requireNonNull(beforeFirstDecision, "beforeFirstDecision");
        if (expected.isEmpty() || expected.stream().anyMatch(s -> !s.isTerminal())) {
            throw new IllegalArgumentException(id + ": expected outcomes must be terminal states");
        }
    }

    /** A row with no change at the merchant during the run. */
    public Scenario(String id, String title, String fault, Set<IntentState> expected, List<String> requiredInvariants,
                    Rules rules, AutoApprover.Policy approver, Driver driver, MerchantId target,
                    List<ProposedLine> basket, String paymentToken) {
        this(id, title, fault, expected, requiredInvariants, rules, approver, driver, target, basket, paymentToken,
                MerchantEvent.NONE);
    }
}
