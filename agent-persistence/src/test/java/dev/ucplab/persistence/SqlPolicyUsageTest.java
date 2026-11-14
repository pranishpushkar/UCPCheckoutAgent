package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.SnapshotLine;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.policy.PolicyConfig;
import dev.ucplab.core.policy.PolicyDecision;
import dev.ucplab.core.policy.PolicyRule;
import dev.ucplab.core.policy.RuleBasedPolicyEngine;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The P3 and P5 counters (9.1) over SQLite: live reads (section 20), keyed by the user (T5). */
class SqlPolicyUsageTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00.000000000Z");
    private static final Duration DAY = Duration.ofHours(24);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final UserId ALICE = new UserId("alice");
    private static final UserId BOB = new UserId("bob");
    private static final MerchantId SHOP = new MerchantId("shop-1");
    /** The 9.1 P3 states: spend that is committed or may have happened. */
    private static final Set<IntentState> COUNTED = EnumSet.of(IntentState.AWAITING_APPROVAL, IntentState.APPROVED,
            IntentState.COMPLETING, IntentState.RECONCILING, IntentState.COMPLETED, IntentState.ESCALATED);

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(NOW);
    private Database db;
    private SqlIntentRepository intents;
    private SqlApprovalRepository approvals;
    private SqlPolicyUsage usage;
    private int n;

    @BeforeEach
    void open() {
        Path file = dir.resolve("usage.db");
        Migrations.migrate(file);
        db = Database.open(file, 2);
        intents = new SqlIntentRepository(db, clock);
        approvals = new SqlApprovalRepository(db);
        usage = new SqlPolicyUsage(db);
    }

    @AfterEach
    void close() {
        db.close();
    }

    /** An intent of {@code owner} in {@code state}, created at {@code createdAt}, with no approval. */
    private IntentId intent(UserId owner, IntentState state, Instant createdAt) {
        IntentId id = new IntentId("i-" + n++);
        PurchaseIntent intent = PurchaseIntent.proposed(id, owner, SHOP, "dedupe-" + id.value(),
                List.of(new ProposedLine("item_123", 1)), owner.value() + "@example.com", createdAt)
                .withState(state, createdAt);
        intents.insert(state == IntentState.COMPLETED ? intent.withOrderId("order-" + id.value()) : intent);
        return id;
    }

    /** An approval of {@code id} for {@code totalMinor}; the newest one inserted is the intent's latest. */
    private void approval(IntentId id, UserId owner, long totalMinor, ApprovalStatus status) {
        ApprovalSnapshot snapshot = new ApprovalSnapshot(id, SHOP, new SessionId("s-" + id.value()),
                List.of(new SnapshotLine("item_123", 1, totalMinor)), "USD", totalMinor, totalMinor,
                NOW.plus(Duration.ofMinutes(10)));
        approvals.insert(new ApprovalRecord("a-" + n++, id, owner, snapshot, snapshot.hash(), "th", snapshot.expiresAt(),
                status, Optional.empty(), Optional.empty()));
    }

    private IntentId spent(UserId owner, IntentState state, Instant createdAt, long totalMinor) {
        IntentId id = intent(owner, state, createdAt);
        approval(id, owner, totalMinor, ApprovalStatus.APPROVED);
        return id;
    }

    private long spendOver24h(UserId user) {
        return usage.committedSpendMinor(user, NOW.minus(DAY), NOW);
    }

    // Covers: P3, 9.1 (each of the six counted states adds its approved total; the four others add nothing)
    @Test
    void spend_countsExactlyThe91States() {
        long expected = 0;
        long amount = 100;
        for (IntentState state : IntentState.values()) {
            spent(ALICE, state, NOW.minusSeconds(60), amount);
            if (COUNTED.contains(state)) {
                expected += amount;
            }
            amount *= 2;
        }

        assertThat(spendOver24h(ALICE)).isEqualTo(expected);
        assertThat(COUNTED).hasSize(6);
    }

    // Covers: P3, 9.1 (an intent being re-quoted counts once: its newest approval only)
    @Test
    void spend_requotedIntent_countsItsNewestApprovalOnce() {
        IntentId id = intent(ALICE, IntentState.AWAITING_APPROVAL, NOW.minusSeconds(60));
        approval(id, ALICE, 15_000, ApprovalStatus.EXPIRED);
        approval(id, ALICE, 16_000, ApprovalStatus.PENDING);

        assertThat(spendOver24h(ALICE)).isEqualTo(16_000);
    }

    // Covers: P3, 9.1 (a counted intent with no approval yet adds nothing)
    @Test
    void spend_intentWithoutApproval_addsNothing() {
        intent(ALICE, IntentState.APPROVED, NOW.minusSeconds(60));

        assertThat(spendOver24h(ALICE)).isZero();
    }

    // Covers: P3, 9.1 (a rolling 24 h from the injected clock: created exactly 24 h ago is inside, 1 ns earlier is
    // outside, and the window's end is exclusive)
    @Test
    void spend_rolling24h_edges() {
        spent(ALICE, IntentState.COMPLETED, NOW.minus(DAY), 1_000);
        spent(ALICE, IntentState.COMPLETED, NOW.minus(DAY).minusNanos(1), 20_000);
        spent(ALICE, IntentState.COMPLETED, NOW.minusNanos(1), 300);
        spent(ALICE, IntentState.COMPLETED, NOW, 4_000_000);

        assertThat(spendOver24h(ALICE)).isEqualTo(1_300);
    }

    // Covers: P3, T5 (bob's spending never counts toward alice's cap)
    @Test
    void spend_isKeyedByUser() {
        spent(ALICE, IntentState.COMPLETED, NOW.minusSeconds(10), 700);
        spent(BOB, IntentState.COMPLETED, NOW.minusSeconds(10), 40_000);

        assertThat(spendOver24h(ALICE)).isEqualTo(700);
        assertThat(spendOver24h(BOB)).isEqualTo(40_000);
        assertThat(spendOver24h(new UserId("carol"))).isZero();
    }

    // Covers: P5, 9.1 (every proposal of the last hour counts, whatever its state; the window's start is inclusive and
    // its end exclusive)
    @Test
    void proposals_lastHour_edges() {
        intent(ALICE, IntentState.REJECTED, NOW.minus(HOUR));
        intent(ALICE, IntentState.AWAITING_APPROVAL, NOW.minus(HOUR).minusNanos(1));
        intent(ALICE, IntentState.QUOTING, NOW.minusSeconds(1));
        intent(ALICE, IntentState.COMPLETED, NOW.minusNanos(1));
        intent(ALICE, IntentState.PROPOSED, NOW);

        assertThat(usage.proposalCount(ALICE, NOW.minus(HOUR), NOW)).isEqualTo(3);
    }

    // Covers: P5, T5 (bob's proposals never count toward alice's rate)
    @Test
    void proposals_areKeyedByUser() {
        intent(ALICE, IntentState.QUOTING, NOW.minusSeconds(5));
        intent(BOB, IntentState.QUOTING, NOW.minusSeconds(5));
        intent(BOB, IntentState.QUOTING, NOW.minusSeconds(4));

        assertThat(usage.proposalCount(ALICE, NOW.minus(HOUR), NOW)).isEqualTo(1);
        assertThat(usage.proposalCount(BOB, NOW.minus(HOUR), NOW)).isEqualTo(2);
    }

    // Covers: P3, P5, T5, section 20 (through the engine, on the injected clock: alice's cap and rate bite, bob's are
    // untouched, and moving the clock past the window frees the cap, because nothing is cached)
    @Test
    void engine_withSqlUsage_appliesP3AndP5PerUserFromTheClock() {
        MerchantEntry shop = new MerchantEntry(SHOP, URI.create("http://127.0.0.1:9001"), "Shop", MerchantStatus.ACTIVE);
        RuleBasedPolicyEngine engine = new RuleBasedPolicyEngine(PolicyConfig.defaults(),
                m -> m.equals(SHOP) ? Optional.of(shop) : Optional.empty(), usage, clock);
        CheckoutView quote = new CheckoutView(new SessionId("s"), CheckoutStatus.READY_FOR_COMPLETE, "USD",
                List.of(new LineView("item_123", 1, 15_000, Optional.empty())), 15_000, 0, 15_000, Optional.empty(),
                List.of());
        spent(ALICE, IntentState.COMPLETED, NOW.minus(DAY).plusSeconds(30), 20_000);
        spent(ALICE, IntentState.COMPLETED, NOW.minusSeconds(30), 20_000);

        PolicyDecision alice = engine.evaluateQuote(ALICE, quote);
        PolicyDecision bob = engine.evaluateQuote(BOB, quote);
        clock.advance(Duration.ofSeconds(31));
        PolicyDecision aliceLater = engine.evaluateQuote(ALICE, quote);

        assertThat(alice.allowed()).isFalse();
        assertThat(alice.ruleId()).contains(PolicyRule.P3);
        assertThat(bob.allowed()).isTrue();
        assertThat(aliceLater.allowed()).as("the first 20000 left the 24 h window").isTrue();

        // One counted proposal is already in the hour; nine more reach the default limit of 10.
        for (int i = 0; i < 9; i++) {
            intent(ALICE, IntentState.REJECTED, clock.instant().minusSeconds(i + 1));
        }
        List<ProposedLine> lines = List.of(new ProposedLine("item_123", 1));
        assertThat(engine.evaluateProposal(ALICE, SHOP, lines).ruleId()).contains(PolicyRule.P5);
        assertThat(engine.evaluateProposal(BOB, SHOP, lines).allowed()).isTrue();
    }
}
