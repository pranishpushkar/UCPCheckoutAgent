package dev.ucplab.eval;

import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.merchant.Money;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * S30 (13.2, 6.4 v9): a follow-up reply that says nothing cheaper, nothing else or nothing that fits is available is
 * checked only if a search in that turn backs it. The reply is read in memory; only the verdict is kept (I8).
 */
final class UncheckedClaim {

    static final String UNCHECKED = "S30 claim: the follow-up said nothing cheaper fits without a search in that turn"
            + " (unchecked claim)";
    static final String STALE = "S30 claim: a follow-up search did not carry the run's changed budget";

    /**
     * A denial ("no", "none", "nothing", "not any", "couldn't find", "isn't anything") and, later in the same sentence,
     * what it denies: something cheaper, else, other, different, that fits, available or within the budget.
     */
    private static final Pattern NOTHING_FITS = Pattern.compile("\\b(?:no|none|nothing|not\\s+any(?:thing)?"
            + "|(?:could\\s*not|couldn['\u2019]t|can['\u2019]t|cannot|did\\s*not|didn['\u2019]t)\\s+find"
            + "|isn['\u2019]t\\s+any(?:thing)?|aren['\u2019]t\\s+any)\\b[^.!?]{0,80}?"
            + "\\b(?:cheaper|less\\s+expensive|lower|else|other|another|different|fits?|available|within)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private UncheckedClaim() {
    }

    /** True when {@code reply} says nothing cheaper, nothing else or nothing that fits is available. */
    static boolean saysNothingFits(String reply) {
        return NOTHING_FITS.matcher(reply).find();
    }

    /** The row's failure, a fixed text, when {@code reply} says nothing fits in a turn that did not search. */
    static Optional<String> check(String reply, boolean searched) {
        return !searched && saysNothingFits(reply) ? Optional.of(UNCHECKED) : Optional.empty();
    }

    /**
     * The row's failure when the follow-up's searches did not carry the run's changed budget: each search's price limit,
     * in order. The near miss's second search has none (6.2); every other must be the changed budget.
     */
    static Optional<String> staleBudget(List<Optional<Money>> limits, Budget changed) {
        Money budget = new Money(changed.amountMinor(), changed.currency());
        boolean filtered = limits.stream().anyMatch(Optional::isPresent);
        boolean stale = limits.stream().flatMap(Optional::stream).anyMatch(l -> !l.equals(budget));
        return !filtered || stale ? Optional.of(STALE) : Optional.empty();
    }
}
