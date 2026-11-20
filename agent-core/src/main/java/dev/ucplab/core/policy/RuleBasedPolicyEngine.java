package dev.ucplab.core.policy;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Table 9.1 rules P1 to P5. Rules are checked in rule-number order and the first failure is
 * returned. The P3 window is the last 24 hours (rolling, so time zones don't matter); the P5
 * window is the last hour. P2 and P3 use the caps of the quote's own currency and that
 * currency's spend only; a quote in a currency with no caps is refused by P2, never converted.
 */
public final class RuleBasedPolicyEngine implements PolicyEngine {

    private static final Duration RATE_WINDOW = Duration.ofHours(1);
    private static final Duration SPEND_WINDOW = Duration.ofHours(24);

    private final PolicyConfig config;
    private final Function<MerchantId, Optional<MerchantEntry>> merchants;
    private final PolicyUsage usage;
    private final Clock clock;

    /**
     * @param merchants registry lookup for P1, normally {@code MerchantRegistry::find} (section 19)
     */
    public RuleBasedPolicyEngine(
            PolicyConfig config,
            Function<MerchantId, Optional<MerchantEntry>> merchants,
            PolicyUsage usage,
            Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.merchants = Objects.requireNonNull(merchants, "merchants");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @throws IllegalArgumentException if {@code lines} is empty: that is invalid input, not a policy outcome */
    @Override
    public PolicyDecision evaluateProposal(UserId user, MerchantId merchant, List<ProposedLine> lines) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("a proposal needs at least one line");
        }

        Optional<MerchantEntry> entry = merchants.apply(merchant);
        if (entry.isEmpty()) {
            return PolicyDecision.deny(PolicyRule.P1, "merchant " + merchant + " is not in the registry");
        }
        if (entry.get().status() != MerchantStatus.ACTIVE) {
            return PolicyDecision.deny(PolicyRule.P1, "merchant " + merchant + " is " + entry.get().status());
        }

        Optional<PolicyDecision> size = checkSize(lines.size(),
                lines.stream().mapToInt(ProposedLine::quantity).max().orElse(0));
        if (size.isPresent()) {
            return size.get();
        }

        Instant now = clock.instant();
        int proposals = usage.proposalCount(user, now.minus(RATE_WINDOW), now);
        if (proposals >= config.maxProposalsPerHour()) {
            return PolicyDecision.deny(PolicyRule.P5, proposals + " proposals in the last hour; limit is "
                    + config.maxProposalsPerHour());
        }
        return PolicyDecision.allow("P1, P4 and P5 passed");
    }

    @Override
    public PolicyDecision evaluateQuote(UserId user, CheckoutView quote, Optional<Budget> budget) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(quote, "quote");

        long total = quote.totalMinor();
        String currency = quote.currency();
        Optional<CurrencyCaps> found = config.capsFor(currency);
        if (found.isEmpty()) {
            return PolicyDecision.deny(PolicyRule.P2, "no per-purchase cap is set for " + currency
                    + "; caps are set for " + String.join(", ", new TreeSet<>(config.caps().keySet())));
        }
        CurrencyCaps caps = found.get();
        if (total > caps.perPurchaseMinor()) {
            return PolicyDecision.deny(PolicyRule.P2, "quoted total " + total + " " + currency
                    + " is above the per-purchase cap " + caps.perPurchaseMinor() + " " + currency);
        }

        Instant now = clock.instant();
        long spent = usage.committedSpendMinor(user, currency, now.minus(SPEND_WINDOW), now);
        // Written as a subtraction so a large spent value cannot overflow.
        if (total > caps.dailyMinor() - spent) {
            return PolicyDecision.deny(PolicyRule.P3, "spent in the last 24 hours " + spent + " plus quoted total "
                    + total + " " + currency + " is above the daily cap " + caps.dailyMinor() + " " + currency);
        }

        Optional<PolicyDecision> size = checkSize(quote.lines().size(),
                quote.lines().stream().mapToInt(LineView::quantity).max().orElse(0));
        if (size.isPresent()) {
            return size.get();
        }
        Objects.requireNonNull(budget, "budget");
        if (budget.isEmpty()) {
            return PolicyDecision.allow("P2, P3 and P4 passed");
        }
        // P9 (9.1): exact, in the budget's own currency; a total in another currency never fits.
        Budget limit = budget.get();
        if (!quote.currency().equals(limit.currency())) {
            return PolicyDecision.deny(PolicyRule.P9, "quoted in " + quote.currency() + "; the budget is in "
                    + limit.currency());
        }
        if (total > limit.amountMinor()) {
            return PolicyDecision.deny(PolicyRule.P9, "quoted total " + total + " is above the budget "
                    + limit.amountMinor());
        }
        return PolicyDecision.allow("P2, P3, P4 and P9 passed");
    }

    private Optional<PolicyDecision> checkSize(int lineCount, int maxQuantity) {
        if (lineCount > config.maxLines()) {
            return Optional.of(PolicyDecision.deny(PolicyRule.P4, lineCount + " lines; limit is " + config.maxLines()));
        }
        if (maxQuantity > config.maxQuantityPerLine()) {
            return Optional.of(PolicyDecision.deny(PolicyRule.P4, "quantity " + maxQuantity
                    + " on one line; limit is " + config.maxQuantityPerLine()));
        }
        return Optional.empty();
    }
}
