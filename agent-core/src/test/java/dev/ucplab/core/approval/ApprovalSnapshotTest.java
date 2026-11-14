package dev.ucplab.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApprovalSnapshotTest {

    static ApprovalSnapshot sample() {
        return new ApprovalSnapshot(
                new IntentId("intent-1"),
                new MerchantId("merchant-a"),
                new SessionId("chk_1001"),
                List.of(new SnapshotLine("item_123", 2, 2500), new SnapshotLine("item_456", 1, 1200)),
                "USD",
                6696,
                6696,
                Instant.parse("2026-11-08T10:10:00Z"));
    }

    // Covers: 9.2 (canonical form). Expected string from Python json.dumps(sort_keys=True, separators=(',',':')),
    // with lines sorted by (itemId, quantity, unitPriceMinor).
    @Test
    void approvalSnapshot_canonicalJson_matchesIndependentEncoder() {
        assertThat(sample().canonicalJson()).isEqualTo(
                "{\"currency\":\"USD\",\"expiresAt\":\"2026-11-08T10:10:00Z\",\"intentId\":\"intent-1\","
                        + "\"lines\":[{\"itemId\":\"item_123\",\"quantity\":2,\"unitPriceMinor\":2500},"
                        + "{\"itemId\":\"item_456\",\"quantity\":1,\"unitPriceMinor\":1200}],"
                        + "\"maxTotalMinor\":6696,\"merchantId\":\"merchant-a\",\"sessionId\":\"chk_1001\","
                        + "\"totalMinor\":6696}");
    }

    // Covers: 9.2 (snapshotHash = sha256_hex(canonicalJson(snapshot))). Golden value from Python hashlib.
    @Test
    void approvalSnapshot_hash_matchesGoldenValue() {
        assertThat(sample().hash()).isEqualTo("37bd4b1e6bfbdb0fde5ee59cf333a620a3ab239b63d2f574c241a56cadb228fa");
    }

    // Covers: 9.2 (lines are sorted by itemId, then quantity, then unitPriceMinor, before hashing)
    @Test
    void approvalSnapshot_hashIgnoresLineOrder_forSameLines() {
        ApprovalSnapshot s = sample();
        ApprovalSnapshot reordered = new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(),
                List.of(s.lines().get(1), s.lines().get(0)), s.currency(), s.totalMinor(), s.maxTotalMinor(),
                s.expiresAt());

        assertThat(reordered.hash()).isEqualTo(s.hash());
        assertThat(reordered.canonicalJson()).isEqualTo(s.canonicalJson());
    }

    // Covers: 9.2 (same itemId on two lines: quantity decides the order)
    @Test
    void approvalSnapshot_hashIgnoresLineOrder_whenItemIdRepeats() {
        ApprovalSnapshot s = sample();
        SnapshotLine one = new SnapshotLine("item_123", 1, 2500);
        SnapshotLine three = new SnapshotLine("item_123", 3, 2500);
        ApprovalSnapshot a = new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(),
                List.of(three, one), s.currency(), 10000, 10000, s.expiresAt());
        ApprovalSnapshot b = new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(),
                List.of(one, three), s.currency(), 10000, 10000, s.expiresAt());

        assertThat(a.hash()).isEqualTo(b.hash());
    }

    // Covers: 9.2 (same itemId and quantity on two lines: unitPriceMinor decides the order)
    @Test
    void approvalSnapshot_hashIgnoresLineOrder_whenLinesDifferOnlyInUnitPrice() {
        ApprovalSnapshot s = sample();
        SnapshotLine cheaper = new SnapshotLine("item_123", 1, 2400);
        SnapshotLine dearer = new SnapshotLine("item_123", 1, 2500);
        ApprovalSnapshot a = new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(),
                List.of(dearer, cheaper), s.currency(), 4900, 4900, s.expiresAt());
        ApprovalSnapshot b = new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(),
                List.of(cheaper, dearer), s.currency(), 4900, 4900, s.expiresAt());

        assertThat(a.hash()).isEqualTo(b.hash());
        assertThat(a.canonicalJson()).contains(
                "[{\"itemId\":\"item_123\",\"quantity\":1,\"unitPriceMinor\":2400},"
                        + "{\"itemId\":\"item_123\",\"quantity\":1,\"unitPriceMinor\":2500}]");
    }

    // Covers: I2, I3, D4 (any change to merchant, session, items, quantities, prices, currency, totals or expiry changes the hash)
    @Test
    void approvalSnapshot_hashChanges_whenAnyFieldChanges() {
        ApprovalSnapshot s = sample();
        List<ApprovalSnapshot> variants = List.of(
                new ApprovalSnapshot(new IntentId("intent-2"), s.merchantId(), s.sessionId(), s.lines(), s.currency(), s.totalMinor(), s.maxTotalMinor(), s.expiresAt()),
                new ApprovalSnapshot(s.intentId(), new MerchantId("merchant-b"), s.sessionId(), s.lines(), s.currency(), s.totalMinor(), s.maxTotalMinor(), s.expiresAt()),
                new ApprovalSnapshot(s.intentId(), s.merchantId(), new SessionId("chk_1002"), s.lines(), s.currency(), s.totalMinor(), s.maxTotalMinor(), s.expiresAt()),
                new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(), List.of(new SnapshotLine("item_123", 3, 2500), s.lines().get(1)), s.currency(), s.totalMinor(), s.maxTotalMinor(), s.expiresAt()),
                new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(), List.of(new SnapshotLine("item_123", 2, 2600), s.lines().get(1)), s.currency(), s.totalMinor(), s.maxTotalMinor(), s.expiresAt()),
                new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(), List.of(new SnapshotLine("item_789", 2, 2500), s.lines().get(1)), s.currency(), s.totalMinor(), s.maxTotalMinor(), s.expiresAt()),
                new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(), s.lines(), "EUR", s.totalMinor(), s.maxTotalMinor(), s.expiresAt()),
                new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(), s.lines(), s.currency(), 6000, s.maxTotalMinor(), s.expiresAt()),
                new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(), s.lines(), s.currency(), s.totalMinor(), 7000, s.expiresAt()),
                new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(), s.lines(), s.currency(), s.totalMinor(), s.maxTotalMinor(), s.expiresAt().plusSeconds(1)));

        assertThat(variants).extracting(ApprovalSnapshot::hash).doesNotContain(s.hash()).doesNotHaveDuplicates();
    }

    // Covers: 9.2 (the snapshot is a value: later changes to the caller's list do not alter it)
    @Test
    void approvalSnapshot_copiesLines_onConstruction() {
        List<SnapshotLine> lines = new ArrayList<>(List.of(new SnapshotLine("item_123", 2, 2500)));
        ApprovalSnapshot s = new ApprovalSnapshot(new IntentId("i"), new MerchantId("m"), new SessionId("s"),
                lines, "USD", 5000, 5000, Instant.EPOCH);
        String before = s.hash();

        lines.add(new SnapshotLine("item_456", 1, 1200));

        assertThat(s.lines()).hasSize(1);
        assertThat(s.hash()).isEqualTo(before);
    }

    // Covers: 9.2, I2 (total may not exceed the approved maximum; no empty or negative snapshots)
    @Test
    void approvalSnapshot_throws_forInvalidValues() {
        ApprovalSnapshot s = sample();

        assertThatThrownBy(() -> new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(), List.of(),
                s.currency(), 0, 0, s.expiresAt())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(), s.lines(),
                s.currency(), 7000, 6696, s.expiresAt())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(), s.lines(),
                " ", 6696, 6696, s.expiresAt())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnapshotLine("item_123", 0, 2500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnapshotLine("item_123", 1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ApprovalSnapshot withShipping(String optionId, String label, long price, String country, long total) {
        ApprovalSnapshot s = sample();
        return new ApprovalSnapshot(s.intentId(), s.merchantId(), s.sessionId(), s.lines(), s.currency(), total, total,
                s.expiresAt(), Optional.of(new FulfillmentSnapshot(optionId, label, price, Optional.of(country))));
    }

    // Covers: 9.2, D4, I2, I3 (the snapshot's canonical form carries fulfillment{optionId, label, priceMinor,
    // destinationCountry} when there is one, and nothing about shipping when there is not: the lab hash is unchanged)
    @Test
    void theShippingPart_isInTheCanonicalForm_andAbsentWhenThereIsNone() {
        ApprovalSnapshot shipped = withShipping("std-ship", "Standard Shipping (Free)", 0, "US", 6696);

        assertThat(shipped.canonicalJson()).contains("\"fulfillment\":{\"destinationCountry\":\"US\","
                + "\"label\":\"Standard Shipping (Free)\",\"optionId\":\"std-ship\",\"priceMinor\":0}");
        assertThat(sample().canonicalJson()).doesNotContain("fulfillment");
        assertThat(sample().hash()).isEqualTo("37bd4b1e6bfbdb0fde5ee59cf333a620a3ab239b63d2f574c241a56cadb228fa");
    }

    // Covers: 9.2, D4, I2, I3, P7 (the hash changes when only the option, its label, its price or the country changes:
    // an approval of the old snapshot cannot authorise the new one)
    @Test
    void theHash_changesWithTheOptionItsPriceOrTheCountry() {
        ApprovalSnapshot base = withShipping("std-ship", "Standard Shipping (Free)", 0, "US", 6696);

        assertThat(withShipping("exp-ship-us", "Standard Shipping (Free)", 0, "US", 6696).hash()).isNotEqualTo(base.hash());
        assertThat(withShipping("std-ship", "Standard Shipping (Free)", 1500, "US", 6696).hash()).isNotEqualTo(base.hash());
        assertThat(withShipping("std-ship", "Express", 0, "US", 6696).hash()).isNotEqualTo(base.hash());
        assertThat(withShipping("std-ship", "Standard Shipping (Free)", 0, "CA", 6696).hash()).isNotEqualTo(base.hash());
        assertThat(base.hash()).isNotEqualTo(sample().hash());
    }

    // Covers: 9.2, D4 (a street address is not a field of the snapshot: only the country is)
    @Test
    void theShippingPart_hasNoAddressField() {
        assertThat(java.util.Arrays.stream(FulfillmentSnapshot.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("optionId", "label", "priceMinor", "destinationCountry");
    }
}
