package dev.ucplab.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The refusals of the approval value types and of the secret (9.2, I2, I3, I8), found unchecked by the H3 P5 mutation
 * pass: what an approval can never hold, and the edge where a value is still allowed. Each has a negative side (refused)
 * and a boundary side (accepted), so a changed comparison fails one of them.
 */
class ApprovalGuardsTest {

    private static final Instant T = Instant.parse("2026-11-08T10:10:00Z");
    private static final List<SnapshotLine> LINES = List.of(new SnapshotLine("item_123", 1, 100));

    private static ApprovalSnapshot snapshot(long total, long max) {
        return new ApprovalSnapshot(new IntentId("i-1"), new MerchantId("m"), new SessionId("s"), LINES, "USD", total, max, T);
    }

    // Covers: 9.2, I2. Kills SnapshotLine.<init>:10 RemoveConditional_EQUAL_ELSE (a blank item id is refused) and
    // SnapshotLine.<init>:16 ConditionalsBoundary (a price of 0 is allowed, a negative one is refused)
    @Test
    void snapshotLine_refusesABlankItemAndANegativePrice_butAllowsFree() {
        assertThatThrownBy(() -> new SnapshotLine(" ", 1, 100)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itemId");
        assertThatThrownBy(() -> new SnapshotLine("item_123", 1, -1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unitPriceMinor");
        assertThatCode(() -> new SnapshotLine("item_123", 1, 0)).doesNotThrowAnyException();
    }

    // Covers: 9.2, I2. Kills ApprovalSnapshot.<init>:60 ConditionalsBoundary and RemoveConditional_ORDER_ELSE (a negative
    // total is refused even when the maximum is not below it; a total of 0 is allowed)
    @Test
    void approvalSnapshot_refusesANegativeTotal_butAllowsZero() {
        assertThatThrownBy(() -> snapshot(-1, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalMinor must not be negative");
        assertThatCode(() -> snapshot(0, 0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> snapshot(5, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> snapshot(5, 5)).doesNotThrowAnyException();
    }

    // Covers: 9.2, D4. Kills FulfillmentSnapshot.<init>:16 RemoveConditional_ORDER_ELSE (a negative shipping price is
    // refused; free shipping is allowed)
    @Test
    void fulfillmentSnapshot_refusesANegativePrice_butAllowsFreeShipping() {
        assertThatThrownBy(() -> new FulfillmentSnapshot("std", "Standard", -1, Optional.of("US")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("priceMinor");
        assertThatCode(() -> new FulfillmentSnapshot("std", "Standard", 0, Optional.of("US"))).doesNotThrowAnyException();
    }

    // Covers: I3, I10. Kills ApprovalRecord.<init>:36 RemoveConditional_EQUAL_ELSE (an approval cannot carry the snapshot
    // of another intent)
    @Test
    void approvalRecord_refusesTheSnapshotOfAnotherIntent() {
        ApprovalSnapshot other = snapshot(100, 100);

        assertThatThrownBy(() -> new ApprovalRecord("a-1", new IntentId("i-2"), new UserId("alice"), other, other.hash(),
                "th", T, ApprovalStatus.PENDING, Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another intent");
        assertThatCode(() -> new ApprovalRecord("a-1", new IntentId("i-1"), new UserId("alice"), other, other.hash(), "th", T,
                ApprovalStatus.PENDING, Optional.empty(), Optional.empty())).doesNotThrowAnyException();
    }

    // Covers: 23.5. Kills ApprovalDecision$Busy.<init>:38 RemoveConditional_EQUAL_ELSE (a busy answer never carries a
    // negative wait; zero is allowed)
    @Test
    void busy_refusesANegativeWait_butAllowsZero() {
        assertThatThrownBy(() -> new ApprovalDecision.Busy(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ApprovalDecision.Busy(Duration.ZERO)).doesNotThrowAnyException();
    }

    // Covers: 9.2, I8. Kills ApprovalSecret.equals:62 (all five mutants) and ApprovalSecret.hashCode:67 PrimitiveReturns: two
    // secrets with the same bytes are equal and hash alike, different ones and other types are not equal, and neither
    // the text of the secret nor its hash code is its string form
    @Test
    void approvalSecret_equalsAndHashCode_followTheKeyBytes() {
        ApprovalSecret a = ApprovalSecret.of("0123456789abcdef0123456789abcdef");
        ApprovalSecret same = ApprovalSecret.of("0123456789abcdef0123456789abcdef");
        ApprovalSecret other = ApprovalSecret.of("fedcba9876543210fedcba9876543210");

        assertThat(a).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(other).isNotEqualTo("0123456789abcdef0123456789abcdef")
                .isNotEqualTo(null);
        assertThat(a.hashCode()).isNotEqualTo(other.hashCode()).isNotZero();
        assertThat(a.toString()).doesNotContain("0123");
    }
}
