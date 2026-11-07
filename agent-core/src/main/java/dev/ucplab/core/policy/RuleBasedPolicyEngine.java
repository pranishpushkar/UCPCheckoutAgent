package dev.ucplab.core.policy;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Table 9.1 rules P1 to P5. Rules are checked in rule-number order and the first failure is
 * returned. The daily window for P3 is the current UTC calendar day; the P5 window is the last hour.
 */
public final class RuleBasedPolicyEngine implements PolicyEngine {

    private static final Duration RATE_WINDOW = Duration.ofHours(1);

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
    public PolicyDecision evaluateQuote(UserId user, CheckoutView quote) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(quote, "quote");

        long total = quote.totalMinor();
        if (total > config.perPurchaseCapMinor()) {
            return PolicyDecision.deny(PolicyRule.P2, "quoted total " + total + " is above the per-purchase cap "
                    + config.perPurchaseCapMinor());
        }

        Instant dayStart = clock.instant().truncatedTo(ChronoUnit.DAYS);
        long spent = usage.committedSpendMinor(user, dayStart, dayStart.plus(Duration.ofDays(1)));
        // Written as a subtraction so a large spent value cannot overflow.
        if (total > config.dailyCapMinor() - spent) {
            return PolicyDecision.deny(PolicyRule.P3, "spent today " + spent + " plus quoted total " + total
                    + " is above the daily cap " + config.dailyCapMinor());
        }

        Optional<PolicyDecision> size = checkSize(quote.lines().size(),
                quote.lines().stream().mapToInt(LineView::quantity).max().orElse(0));
        if (size.isPresent()) {
            return size.get();
        }
        return PolicyDecision.allow("P2, P3 and P4 passed");
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
