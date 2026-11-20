package dev.ucplab.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.SessionId;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RuleBasedPolicyEngineTest {

    private static final Instant NOW = Instant.parse("2026-11-08T15:30:00Z");
    private static final UserId ALICE = new UserId("alice");
    private static final UserId BOB = new UserId("bob");
    private static final MerchantId ACTIVE = new MerchantId("merchant-a");
    private static final MerchantId SUSPENDED = new MerchantId("merchant-s");
    private static final MerchantId UNKNOWN = new MerchantId("evil.example");

    private final FakeUsage usage = new FakeUsage();
    private final PolicyEngine engine = new RuleBasedPolicyEngine(
            PolicyConfig.defaults(), RuleBasedPolicyEngineTest::registry, usage, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Optional<MerchantEntry> registry(MerchantId id) {
        if (id.equals(ACTIVE)) {
            return Optional.of(new MerchantEntry(id, URI.create("http://localhost:8080"), "A", MerchantStatus.ACTIVE));
        }
        if (id.equals(SUSPENDED)) {
            return Optional.of(new MerchantEntry(id, URI.create("http://localhost:8081"), "S", MerchantStatus.SUSPENDED));
        }
        return Optional.empty();
    }

    private static List<ProposedLine> lines(int count, int quantity) {
        return IntStream.range(0, count).mapToObj(i -> new ProposedLine("item_" + i, quantity)).toList();
    }

    private static CheckoutView quote(long totalMinor, List<LineView> lines) {
        return quote(totalMinor, "USD", lines);
    }

    private static CheckoutView quote(long totalMinor, String currency, List<LineView> lines) {
        return new CheckoutView(new SessionId("chk_1"), CheckoutStatus.READY_FOR_COMPLETE, currency, lines,
                totalMinor, 0, totalMinor, Optional.empty(), List.of());
    }

    private static CheckoutView inr(long totalMinor) {
        return quote(totalMinor, "INR", List.of(new LineView("item_123", 1, totalMinor)));
    }

    private PolicyEngine withCaps(Map<String, CurrencyCaps> caps) {
        return new RuleBasedPolicyEngine(new PolicyConfig(caps, 5, 5, 10, true, 2),
                RuleBasedPolicyEngineTest::registry, usage, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final Map<String, CurrencyCaps> USD_AND_INR = Map.of("USD", new CurrencyCaps(20_000, 50_000),
            "INR", new CurrencyCaps(2_000_000, 5_000_000));

    // Covers: P2, 9.1 caps per currency, 26.7 (an INR quote of 15,000.00 is under INR's cap of 20,000.00 and allowed,
    // though its minor units are far above USD's cap; one paisa over INR's cap is refused by P2)
    @Test
    void evaluateQuote_checksAnInrQuote_againstTheInrCap() {
        PolicyEngine engine = withCaps(USD_AND_INR);

        assertThat(engine.evaluateQuote(ALICE, inr(1_500_000), Optional.empty()).allowed()).isTrue();
        assertThat(engine.evaluateQuote(ALICE, inr(2_000_000), Optional.empty()).allowed()).isTrue();
        PolicyDecision over = engine.evaluateQuote(ALICE, inr(2_000_001), Optional.empty());
        assertThat(over.ruleId()).contains(PolicyRule.P2);
        assertThat(over.reason()).contains("INR").contains("2000000");
        assertThat(engine.evaluateQuote(ALICE, quote(20_001), Optional.empty()).ruleId()).contains(PolicyRule.P2);
    }

    // Covers: P2, 9.1 caps per currency (an INR quote under a USD-sized cap, with caps set for USD only, is refused by
    // P2 with a reason that names both currencies; it is never converted, however small)
    @Test
    void evaluateQuote_refusesAQuoteInACurrencyWithNoCaps_neverConverting() {
        PolicyDecision d = engine.evaluateQuote(ALICE, inr(100), Optional.empty());

        assertThat(d.allowed()).isFalse();
        assertThat(d.ruleId()).contains(PolicyRule.P2);
        assertThat(d.reason()).isEqualTo("no per-purchase cap is set for INR; caps are set for USD");
    }

    // Covers: P3, 9.1 caps per currency (the daily cap is the quote's currency's, against that currency's spend only:
    // 4,000,000 paise spent leaves 1,000,000 under INR's 5,000,000; the USD quote is checked against USD's spend)
    @Test
    void evaluateQuote_checksP3_inTheQuotesCurrency() {
        PolicyEngine engine = withCaps(USD_AND_INR);
        usage.spendIn.put("INR", 4_000_000L);
        usage.spendIn.put("USD", 45_000L);

        assertThat(engine.evaluateQuote(ALICE, inr(1_000_000), Optional.empty()).allowed()).isTrue();
        assertThat(engine.evaluateQuote(ALICE, inr(1_000_001), Optional.empty()).ruleId()).contains(PolicyRule.P3);
        assertThat(engine.evaluateQuote(ALICE, quote(5_001), Optional.empty()).ruleId()).contains(PolicyRule.P3);
        assertThat(usage.currencies).containsExactly("INR", "INR", "USD");
    }

    private static CheckoutView quote(long totalMinor) {
        return quote(totalMinor, List.of(new LineView("item_123", 1, totalMinor)));
    }

    // Covers: P1, P4, P5, FR7 (a proposal within every limit is allowed)
    @Test
    void evaluateProposal_allows_whenEveryRulePasses() {
        PolicyDecision d = engine.evaluateProposal(ALICE, ACTIVE, lines(5, 5));

        assertThat(d.allowed()).isTrue();
        assertThat(d.ruleId()).isEmpty();
    }

    // Covers: P1, FR7, S11 (merchant not in the registry)
    @Test
    void evaluateProposal_deniesWithP1_whenMerchantNotInRegistry() {
        PolicyDecision d = engine.evaluateProposal(ALICE, UNKNOWN, lines(1, 1));

        assertThat(d.allowed()).isFalse();
        assertThat(d.ruleId()).contains(PolicyRule.P1);
    }

    // Covers: P1, FR7, section 19 (only ACTIVE merchants take new intents)
    @Test
    void evaluateProposal_deniesWithP1_whenMerchantSuspended() {
        PolicyDecision d = engine.evaluateProposal(ALICE, SUSPENDED, lines(1, 1));

        assertThat(d.ruleId()).contains(PolicyRule.P1);
        assertThat(d.reason()).contains("SUSPENDED");
    }

    // Covers: P4, FR7 (more than 5 lines)
    @Test
    void evaluateProposal_deniesWithP4_whenMoreThanMaxLines() {
        assertThat(engine.evaluateProposal(ALICE, ACTIVE, lines(6, 1)).ruleId()).contains(PolicyRule.P4);
    }

    // Covers: P4, FR7 (more than 5 on one line)
    @Test
    void evaluateProposal_deniesWithP4_whenQuantityAboveMaxPerLine() {
        List<ProposedLine> proposal = List.of(new ProposedLine("item_123", 1), new ProposedLine("item_456", 6));

        assertThat(engine.evaluateProposal(ALICE, ACTIVE, proposal).ruleId()).contains(PolicyRule.P4);
    }

    // Covers: P5, T5 (the 10th proposal in an hour is allowed, the 11th is denied)
    @Test
    void evaluateProposal_deniesWithP5_atHourlyLimit() {
        usage.proposals.put(ALICE, 9);
        assertThat(engine.evaluateProposal(ALICE, ACTIVE, lines(1, 1)).allowed()).isTrue();

        usage.proposals.put(ALICE, 10);
        assertThat(engine.evaluateProposal(ALICE, ACTIVE, lines(1, 1)).ruleId()).contains(PolicyRule.P5);
    }

    // Covers: P5, T5 (the counter is per user: another user's proposals do not count)
    @Test
    void evaluateProposal_countsOnlyTheUsersOwnProposals_forP5() {
        usage.proposals.put(BOB, 50);

        assertThat(engine.evaluateProposal(ALICE, ACTIVE, lines(1, 1)).allowed()).isTrue();
    }

    // Covers: P5, section 20 (the window is the last hour, read live from the clock)
    @Test
    void evaluateProposal_asksForTheLastHour_forP5() {
        engine.evaluateProposal(ALICE, ACTIVE, lines(1, 1));

        assertThat(usage.windows).containsExactly(new Window(ALICE, NOW.minusSeconds(3600), NOW));
    }

    // Covers: P1, P4, P5 (rules are checked in rule-number order; the first failure is reported)
    @Test
    void evaluateProposal_reportsFirstFailingRule_whenSeveralFail() {
        usage.proposals.put(ALICE, 99);

        assertThat(engine.evaluateProposal(ALICE, UNKNOWN, lines(9, 9)).ruleId()).contains(PolicyRule.P1);
        assertThat(engine.evaluateProposal(ALICE, ACTIVE, lines(9, 9)).ruleId()).contains(PolicyRule.P4);
    }

    // Covers: FR7 (an empty proposal is invalid input, not a policy outcome)
    @Test
    void evaluateProposal_throws_whenNoLines() {
        assertThatThrownBy(() -> engine.evaluateProposal(ALICE, ACTIVE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: P2, P3, P4, FR7 (a quote within every limit is allowed)
    @Test
    void evaluateQuote_allows_whenEveryRulePasses() {
        assertThat(engine.evaluateQuote(ALICE, quote(20_000), Optional.empty()).allowed()).isTrue();
    }

    // Covers: P2, FR7 (a total of exactly the cap is allowed; one minor unit more is denied)
    @Test
    void evaluateQuote_deniesWithP2_aboveCap() {
        assertThat(engine.evaluateQuote(ALICE, quote(20_000), Optional.empty()).allowed()).isTrue();
        assertThat(engine.evaluateQuote(ALICE, quote(20_001), Optional.empty()).ruleId()).contains(PolicyRule.P2);
    }

    // Covers: P9, 9.1 (exact: a total equal to the user's budget is allowed; one minor unit more is denied with P9)
    @Test
    void evaluateQuote_deniesWithP9_aboveTheBudget_andAllowsTheBudgetExactly() {
        Optional<Budget> budget = Optional.of(new Budget(5_000, "USD"));

        assertThat(engine.evaluateQuote(ALICE, quote(5_000), budget).allowed()).isTrue();
        PolicyDecision over = engine.evaluateQuote(ALICE, quote(5_001), budget);
        assertThat(over.allowed()).isFalse();
        assertThat(over.ruleId()).contains(PolicyRule.P9);
        assertThat(over.reason()).contains("5001").contains("5000");
    }

    // Covers: P9, 9.1 (a quote in another currency than the budget's is denied with P9, even when the number fits)
    @Test
    void evaluateQuote_deniesWithP9_whenTheCurrencyDiffers() {
        PolicyDecision d = engine.evaluateQuote(ALICE, quote(100), Optional.of(new Budget(5_000, "EUR")));

        assertThat(d.ruleId()).contains(PolicyRule.P9);
        assertThat(d.reason()).contains("USD").contains("EUR");
    }

    // Covers: P9, 9.1 (an intent without a budget skips P9; P2 still names the first failing rule before P9)
    @Test
    void evaluateQuote_skipsP9_withoutABudget_andP2ComesFirst() {
        assertThat(engine.evaluateQuote(ALICE, quote(19_999), Optional.empty()).allowed()).isTrue();
        assertThat(engine.evaluateQuote(ALICE, quote(20_001), Optional.of(new Budget(10, "USD"))).ruleId())
                .contains(PolicyRule.P2);
        assertThat(engine.evaluateQuote(ALICE, quote(15), Optional.of(new Budget(10, "USD"))).ruleId())
                .contains(PolicyRule.P9);
    }

    // Covers: P3, T5 (spent over the last 24 hours + quote may reach the cap but not exceed it)
    @Test
    void evaluateQuote_deniesWithP3_when24HourTotalWouldExceedCap() {
        usage.spend.put(ALICE, 35_000L);

        assertThat(engine.evaluateQuote(ALICE, quote(15_000), Optional.empty()).allowed()).isTrue();
        assertThat(engine.evaluateQuote(ALICE, quote(15_001), Optional.empty()).ruleId()).contains(PolicyRule.P3);
    }

    // Covers: P3, T5 (spend is per user: another user's spend does not count)
    @Test
    void evaluateQuote_countsOnlyTheUsersOwnSpend_forP3() {
        usage.spend.put(BOB, 50_000L);

        assertThat(engine.evaluateQuote(ALICE, quote(10_000), Optional.empty()).allowed()).isTrue();
    }

    // Covers: P3 (spend already above the cap denies without overflow)
    @Test
    void evaluateQuote_deniesWithP3_whenSpendAlreadyHuge() {
        usage.spend.put(ALICE, Long.MAX_VALUE);

        assertThat(engine.evaluateQuote(ALICE, quote(1), Optional.empty()).ruleId()).contains(PolicyRule.P3);
    }

    // Covers: P3, section 20 (the window is the rolling last 24 hours, read live from the clock, not a calendar day)
    @Test
    void evaluateQuote_asksForTheLast24Hours_forP3() {
        engine.evaluateQuote(ALICE, quote(100), Optional.empty());

        assertThat(usage.windows).containsExactly(new Window(ALICE, Instant.parse("2026-11-07T15:30:00Z"), NOW));
    }

    // Covers: P3 (the window moves with the clock: just after UTC midnight it still reaches into yesterday)
    @Test
    void evaluateQuote_usesRollingWindow_acrossUtcMidnight() {
        Instant justAfterMidnight = Instant.parse("2026-11-09T00:05:00Z");
        PolicyEngine late = new RuleBasedPolicyEngine(PolicyConfig.defaults(),
                RuleBasedPolicyEngineTest::registry, usage, Clock.fixed(justAfterMidnight, ZoneOffset.UTC));

        late.evaluateQuote(ALICE, quote(100), Optional.empty());

        assertThat(usage.windows).containsExactly(
                new Window(ALICE, Instant.parse("2026-11-08T00:05:00Z"), justAfterMidnight));
    }

    // Covers: P4, FR7, table 7.2 (QUOTING guard: policy re-checks the merchant's quoted items)
    @Test
    void evaluateQuote_deniesWithP4_whenQuotedItemsExceedLimits() {
        List<LineView> sixLines = IntStream.range(0, 6).mapToObj(i -> new LineView("item_" + i, 1, 10)).toList();
        List<LineView> bigLine = List.of(new LineView("item_123", 6, 10));

        assertThat(engine.evaluateQuote(ALICE, quote(60, sixLines), Optional.empty()).ruleId()).contains(PolicyRule.P4);
        assertThat(engine.evaluateQuote(ALICE, quote(60, bigLine), Optional.empty()).ruleId()).contains(PolicyRule.P4);
    }

    // Covers: P2, P3 (P2 is reported before P3 when both fail)
    @Test
    void evaluateQuote_reportsP2BeforeP3_whenBothFail() {
        usage.spend.put(ALICE, 50_000L);

        assertThat(engine.evaluateQuote(ALICE, quote(30_000), Optional.empty()).ruleId()).contains(PolicyRule.P2);
    }

    private record Window(UserId user, Instant from, Instant to) {
    }

    // Covers: P3, P2, 9.1, 9.2, H1 D4 (shipping counts: a quote whose lines fit under the daily cap but whose total with
    // the chosen option's price takes the day over it is refused by P3; the same lines with free shipping are allowed.
    // The total the engine compares is the merchant's total, which includes the fulfillment line; the daily spend is the
    // sum of the approved snapshots' totals, which include it too)
    @Test
    void shippingCountsAgainstTheDailyCap() {
        usage.spend.put(ALICE, 44_000L);
        var line = new dev.ucplab.core.merchant.LineView("item_123", 2, 2500, Optional.of("li_1"));
        var free = new dev.ucplab.core.merchant.CheckoutView(new dev.ucplab.core.merchant.SessionId("s"),
                dev.ucplab.core.merchant.CheckoutStatus.READY_FOR_COMPLETE, "USD", List.of(line), 5000, 400, 5400,
                Optional.empty(), Optional.empty(), List.of());
        var shipped = new dev.ucplab.core.merchant.CheckoutView(new dev.ucplab.core.merchant.SessionId("s"),
                dev.ucplab.core.merchant.CheckoutStatus.READY_FOR_COMPLETE, "USD", List.of(line), 5000, 400, 5400 + 1500,
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(), 1500);

        assertThat(engine.evaluateQuote(ALICE, free, Optional.empty()).allowed()).isTrue();
        assertThat(engine.evaluateQuote(ALICE, shipped, Optional.empty()).allowed()).isFalse();
        assertThat(engine.evaluateQuote(ALICE, shipped, Optional.empty()).ruleId().orElseThrow().name()).isEqualTo("P3");
    }

    private static final class FakeUsage implements PolicyUsage {
        final Map<UserId, Long> spend = new HashMap<>();
        final Map<UserId, Integer> proposals = new HashMap<>();
        final List<Window> windows = new ArrayList<>();
        /** When set, the spend of each currency asked for, for every user; else {@link #spend}. */
        final Map<String, Long> spendIn = new HashMap<>();
        final List<String> currencies = new ArrayList<>();

        @Override
        public long committedSpendMinor(UserId user, String currency, Instant from, Instant to) {
            windows.add(new Window(user, from, to));
            currencies.add(currency);
            return spendIn.isEmpty() ? spend.getOrDefault(user, 0L) : spendIn.getOrDefault(currency, 0L);
        }

        @Override
        public int proposalCount(UserId user, Instant from, Instant to) {
            windows.add(new Window(user, from, to));
            return proposals.getOrDefault(user, 0);
        }
    }
}
