package dev.ucplab.core.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.SnapshotLine;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The behaviour every {@link RecoveryScan} must have (7.6): run against the in-memory and the SQL adapter. */
public abstract class RecoveryScanContract {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final UserId ALICE = new UserId("alice");
    private static final MerchantId SHOP = new MerchantId("shop-1");

    protected final MutableClock clock = new MutableClock(T0);
    protected IntentRepository intents;
    protected ApprovalRepository approvals;
    protected RecoveryScan scan;

    /** Sets {@link #intents}, {@link #approvals} and {@link #scan}: fresh, empty and sharing one store. */
    protected abstract void create(MutableClock clock);

    @BeforeEach
    void createStore() {
        create(clock);
    }

    private PurchaseIntent insert(String id, IntentState state) {
        PurchaseIntent intent = PurchaseIntent.proposed(new IntentId(id), ALICE, SHOP, "d-" + id,
                List.of(new ProposedLine("item_123", 1)), "a@example.com", clock.instant())
                .withState(state, clock.instant());
        intents.insert(intent);
        return intent;
    }

    private void approval(String id, String intentId, ApprovalStatus status, Instant expiresAt) {
        IntentId intent = new IntentId(intentId);
        ApprovalSnapshot snapshot = new ApprovalSnapshot(intent, SHOP, new SessionId("s-" + id),
                List.of(new SnapshotLine("item_123", 1, 2500)), "USD", 2500, 2500, expiresAt);
        approvals.insert(new ApprovalRecord(id, intent, ALICE, snapshot, snapshot.hash(), "th-" + id, expiresAt,
                ApprovalStatus.PENDING, Optional.empty(), Optional.empty()));
        if (status != ApprovalStatus.PENDING) {
            approvals.transition(id, ApprovalStatus.PENDING, status, clock.instant(), Optional.empty());
        }
    }

    // Covers: 7.6 (an AWAITING_APPROVAL intent whose pending approval has expired is due; one that has not, is not)
    @Test
    void approvalsDue_returnsIntent_onlyOnceItsPendingApprovalHasExpired() {
        insert("i1", IntentState.AWAITING_APPROVAL);
        approval("a1", "i1", ApprovalStatus.PENDING, T0.plusSeconds(600));

        assertThat(scan.approvalsDue(clock.instant(), 10)).isEmpty();
        assertThat(scan.approvalsDue(T0.plusSeconds(599), 10)).isEmpty();
        assertThat(scan.approvalsDue(T0.plusSeconds(600), 10)).containsExactly(new IntentId("i1"));
    }

    // Covers: 7.6 (only intents still AWAITING_APPROVAL: a stale PENDING approval of a decided intent is ignored)
    @Test
    void approvalsDue_ignoresIntentsThatAreNotAwaitingApproval() {
        insert("i1", IntentState.REJECTED);
        approval("a1", "i1", ApprovalStatus.PENDING, T0.plusSeconds(10));
        insert("i2", IntentState.APPROVED);
        approval("a2", "i2", ApprovalStatus.PENDING, T0.plusSeconds(10));
        insert("i3", IntentState.QUOTING);

        assertThat(scan.approvalsDue(T0.plusSeconds(60), 10)).isEmpty();
    }

    // Covers: 7.6 (an approval already EXPIRED with the intent still AWAITING_APPROVAL is due too)
    @Test
    void approvalsDue_returnsIntent_whoseApprovalIsAlreadyExpired() {
        insert("i1", IntentState.AWAITING_APPROVAL);
        approval("a1", "i1", ApprovalStatus.EXPIRED, T0.plusSeconds(10));

        assertThat(scan.approvalsDue(clock.instant(), 10)).containsExactly(new IntentId("i1"));
    }

    // Covers: 7.6, 9.2 (only the newest approval counts: an old expired one beside a live new one is not due)
    @Test
    void approvalsDue_looksAtNewestApprovalOnly() {
        insert("i1", IntentState.AWAITING_APPROVAL);
        approval("a1", "i1", ApprovalStatus.EXPIRED, T0.plusSeconds(10));
        approval("a2", "i1", ApprovalStatus.PENDING, T0.plusSeconds(900));

        assertThat(scan.approvalsDue(T0.plusSeconds(60), 10)).isEmpty();
    }

    // Covers: 7.6 (an intent whose lease another worker holds is left for it, so the scan does not cause intent_busy)
    @Test
    void approvalsDue_skipsIntentsWithALiveLease() {
        insert("i1", IntentState.AWAITING_APPROVAL);
        approval("a1", "i1", ApprovalStatus.PENDING, T0.plusSeconds(10));
        assertThat(intents.claim(new IntentId("i1"), "worker-1", LEASE)).isPresent();

        assertThat(scan.approvalsDue(T0.plusSeconds(20), 10)).isEmpty();
        assertThat(scan.approvalsDue(T0.plusSeconds(31), 10)).containsExactly(new IntentId("i1"));
    }

    // Covers: 7.6 (oldest expiry first, at most limit)
    @Test
    void approvalsDue_ordersByExpiryAndHonoursLimit() {
        insert("i1", IntentState.AWAITING_APPROVAL);
        insert("i2", IntentState.AWAITING_APPROVAL);
        insert("i3", IntentState.AWAITING_APPROVAL);
        approval("a1", "i1", ApprovalStatus.PENDING, T0.plusSeconds(300));
        approval("a2", "i2", ApprovalStatus.PENDING, T0.plusSeconds(100));
        approval("a3", "i3", ApprovalStatus.PENDING, T0.plusSeconds(200));

        assertThat(scan.approvalsDue(T0.plusSeconds(400), 2))
                .containsExactly(new IntentId("i2"), new IntentId("i3"));
    }

    // Covers: 7.6 (the scan takes no lease and changes nothing)
    @Test
    void approvalsDue_changesNothing() {
        insert("i1", IntentState.AWAITING_APPROVAL);
        approval("a1", "i1", ApprovalStatus.PENDING, T0.plusSeconds(10));

        scan.approvalsDue(T0.plusSeconds(60), 10);

        assertThat(intents.findForSystem(new IntentId("i1")).orElseThrow().leaseOwner()).isEmpty();
        assertThat(approvals.findLatestForSystem(new IntentId("i1")).orElseThrow().status())
                .isEqualTo(ApprovalStatus.PENDING);
    }

    // Covers: 7.6 (a limit below 1 is a programming error)
    @Test
    void approvalsDue_throws_whenLimitIsBelowOne() {
        assertThatThrownBy(() -> scan.approvalsDue(T0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
