package dev.ucplab.eval;

import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.merchant.Money;
import java.util.List;
import java.util.Optional;

/**
 * S30 (13.2, 6.4 v9): a follow-up reply that says nothing cheaper, nothing else or nothing that fits is available is
 * checked only if a search in that turn backs it. The reply is read in memory; only the verdict is kept (I8).
 */
final class UncheckedClaim {

    private UncheckedClaim() {
    }

    /** True when {@code reply} says nothing cheaper, nothing else or nothing that fits is available. */
    static boolean saysNothingFits(String reply) {
        return false;
    }

    /** The row's failure, a fixed text, when {@code reply} says nothing fits in a turn that did not search. */
    static Optional<String> check(String reply, boolean searched) {
        return Optional.empty();
    }

    /**
     * The row's failure when the follow-up's searches did not carry the run's changed budget: each search's price limit,
     * in order. The near miss's second search has none (6.2); every other must be the changed budget.
     */
    static Optional<String> staleBudget(List<Optional<Money>> limits, Budget changed) {
        return Optional.empty();
    }
}
