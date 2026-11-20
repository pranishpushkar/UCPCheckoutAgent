package dev.ucplab.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.VariantOption;
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

    private static ApprovalSnapshot titled(String title) {
        return new ApprovalSnapshot(new IntentId("intent-1"), new MerchantId("merchant-a"), new SessionId("chk_1001"),
                List.of(new SnapshotLine("item_456", 1, 1200),
                        new SnapshotLine("gid://shopify/ProductVariant/1", 1, 69995, title)),
                "USD", 71195, 71195, Instant.parse("2026-11-08T10:10:00Z"));
    }

    // Covers: 9.2, D4, I2 (a line's non-empty title is in the canonical form; an empty one is left out, so a snapshot
    // without titles hashes exactly as before). Expected string and hash from Python json.dumps(sort_keys=True,
    // separators=(',',':')) and hashlib.
    @Test
    void aLinesTitle_isInTheCanonicalForm_andAnEmptyOneIsLeftOut() {
        ApprovalSnapshot snowboard = titled("The Complete Snowboard - Ice");

        assertThat(snowboard.canonicalJson()).isEqualTo("{\"currency\":\"USD\",\"expiresAt\":\"2026-11-08T10:10:00Z\","
                + "\"intentId\":\"intent-1\",\"lines\":[{\"itemId\":\"gid://shopify/ProductVariant/1\",\"quantity\":1,"
                + "\"title\":\"The Complete Snowboard - Ice\",\"unitPriceMinor\":69995},{\"itemId\":\"item_456\","
                + "\"quantity\":1,\"unitPriceMinor\":1200}],\"maxTotalMinor\":71195,\"merchantId\":\"merchant-a\","
                + "\"sessionId\":\"chk_1001\",\"totalMinor\":71195}");
        assertThat(snowboard.hash()).isEqualTo("9dfabfdadce2523a6e90377605a091107753791b4f6ec4f79ee3236b465d8c9e");
        assertThat(titled("").canonicalJson()).doesNotContain("title");
        assertThat(sample().hash()).isEqualTo("37bd4b1e6bfbdb0fde5ee59cf333a620a3ab239b63d2f574c241a56cadb228fa");
    }

    private static ApprovalSnapshot sized(List<VariantOption> options) {
        return new ApprovalSnapshot(new IntentId("intent-1"), new MerchantId("merchant-a"), new SessionId("chk_1001"),
                List.of(new SnapshotLine("item_456", 1, 1200),
                        new SnapshotLine("gid://shopify/ProductVariant/1", 1, 69995, "Arbor Element Decon 2026", options)),
                "USD", 71195, 71195, Instant.parse("2026-11-08T10:10:00Z"));
    }

    // Covers: 9.2, D4, I2, I3 (a line's options are in the canonical form, like its title, and hashed: a different size
    // is a different snapshot; an empty list is left out, so a snapshot without options hashes exactly as before).
    // Expected string and hash from Python json.dumps(sort_keys=True, separators=(',',':')) and hashlib.
    @Test
    void aLinesOptions_areInTheCanonicalForm_hashed_andLeftOutWhenEmpty() {
        ApprovalSnapshot board = sized(List.of(new VariantOption("Size", "154 cm")));

        assertThat(board.canonicalJson()).isEqualTo("{\"currency\":\"USD\",\"expiresAt\":\"2026-11-08T10:10:00Z\","
                + "\"intentId\":\"intent-1\",\"lines\":[{\"itemId\":\"gid://shopify/ProductVariant/1\","
                + "\"options\":[{\"label\":\"154 cm\",\"name\":\"Size\"}],\"quantity\":1,"
                + "\"title\":\"Arbor Element Decon 2026\",\"unitPriceMinor\":69995},{\"itemId\":\"item_456\","
                + "\"quantity\":1,\"unitPriceMinor\":1200}],\"maxTotalMinor\":71195,\"merchantId\":\"merchant-a\","
                + "\"sessionId\":\"chk_1001\",\"totalMinor\":71195}");
        assertThat(board.hash()).isEqualTo("5873bb05df1198bb2bbfb8ab90ca8a0a7c06fc64c6ec16e3fa76e3497a93c005");
        assertThat(board.hash()).isNotEqualTo(sized(List.of(new VariantOption("Size", "158 cm"))).hash())
                .isNotEqualTo(sized(List.of()).hash());
        assertThat(sized(List.of()).canonicalJson()).doesNotContain("options");
        assertThat(sample().hash()).isEqualTo("37bd4b1e6bfbdb0fde5ee59cf333a620a3ab239b63d2f574c241a56cadb228fa");
    }

    // Covers: 9.2, D4, I2, I3 (the hash changes when only a title changes: an approval of one title cannot authorise
    // another; and lines that differ only in their titles hash the same in any order)
    @Test
    void theHash_changesWithTheTitle_andIgnoresTheOrderOfLinesDifferingOnlyInTitle() {
        assertThat(titled("The Complete Snowboard - Ice").hash()).isNotEqualTo(titled("The Complete Snowboard").hash())
                .isNotEqualTo(titled("").hash());
        SnapshotLine a = new SnapshotLine("item_1", 1, 100, "A");
        SnapshotLine b = new SnapshotLine("item_1", 1, 100, "B");
        ApprovalSnapshot ab = new ApprovalSnapshot(new IntentId("i"), new MerchantId("m"), new SessionId("s"),
                List.of(a, b), "USD", 200, 200, Instant.parse("2026-11-08T10:10:00Z"));
        ApprovalSnapshot ba = new ApprovalSnapshot(new IntentId("i"), new MerchantId("m"), new SessionId("s"),
                List.of(b, a), "USD", 200, 200, Instant.parse("2026-11-08T10:10:00Z"));
        assertThat(ab.hash()).isEqualTo(ba.hash());
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

    // ---------------------------------------------------------------- link (26.7)

    static final String LINK = "https://www.shop-a.example/buy/1000001:1?_gsid=FAKEGSID0001"
            + "&attribution%2Fclick_id_tag=FAKETAG";

    static ApprovalSnapshot linked(LinkSnapshot link) {
        return new ApprovalSnapshot(new IntentId("intent-1"), new MerchantId("shopify-catalog"),
                new SessionId("link:gid://shopify/ProductVariant/1000001"),
                List.of(new SnapshotLine("gid://shopify/ProductVariant/1000001", 1, 30495, "Example Snowboard 1")),
                "USD", 30495, 30495, Instant.parse("2026-11-08T10:10:00Z"), Optional.empty(), Optional.of(link));
    }

    static LinkSnapshot link(String name, String domain, Optional<String> site, String url) {
        return new LinkSnapshot(name, domain, site, dev.ucplab.core.merchant.ContinueUrl.parse(url).orElseThrow());
    }

    static final LinkSnapshot SHOP_A = link("Example Shop A", "shop-a.myshopify.example",
            Optional.of("www.shop-a.example"), LINK);

    // Covers: 9.2, I2, I3, 26.7 (a link intent's snapshot carries link{sellerName, sellerDomain, sellerSite, url}, the
    // url exactly as read; sellerSite only when present; a snapshot without link hashes exactly as before). Expected
    // string and hashes from Python json.dumps(sort_keys=True, separators=(',',':')) and hashlib.
    @Test
    void theLink_isInTheCanonicalForm_andAbsentWhenThereIsNone() {
        ApprovalSnapshot s = linked(SHOP_A);

        assertThat(s.canonicalJson()).isEqualTo("{\"currency\":\"USD\",\"expiresAt\":\"2026-11-08T10:10:00Z\","
                + "\"intentId\":\"intent-1\",\"lines\":[{\"itemId\":\"gid://shopify/ProductVariant/1000001\","
                + "\"quantity\":1,\"title\":\"Example Snowboard 1\",\"unitPriceMinor\":30495}],"
                + "\"link\":{\"sellerDomain\":\"shop-a.myshopify.example\",\"sellerName\":\"Example Shop A\","
                + "\"sellerSite\":\"www.shop-a.example\",\"url\":\"" + LINK + "\"},\"maxTotalMinor\":30495,"
                + "\"merchantId\":\"shopify-catalog\",\"sessionId\":\"link:gid://shopify/ProductVariant/1000001\","
                + "\"totalMinor\":30495}");
        assertThat(s.hash()).isEqualTo("25d64f9a2fb0bdbc7c37f11ca53605859486f3b57080ea0614abb6653dc88186");
        assertThat(linked(link("Example Shop A", "shop-a.myshopify.example", Optional.empty(), LINK)).hash())
                .isEqualTo("e9f8efa41135e164a412d6a6dca76ed273b87e6bb8eca04e682122f3a9f3090f");
        assertThat(sample().canonicalJson()).doesNotContain("link");
        assertThat(sample().hash()).isEqualTo("37bd4b1e6bfbdb0fde5ee59cf333a620a3ab239b63d2f574c241a56cadb228fa");
    }

    // Covers: 9.2, I2, I3, 23.1 (a link's shipsTo, the intent's buyer country, is in the canonical form and hashed; a link
    // without one hashes exactly as before). Expected string and hashes from Python json.dumps(sort_keys=True,
    // separators=(',',':')) and hashlib.
    @Test
    void aLinksShipsTo_isHashed_andALinkWithoutOneHashesAsBefore() {
        LinkSnapshot india = new LinkSnapshot("Example Shop A", "shop-a.myshopify.example",
                Optional.of("www.shop-a.example"), Optional.of("IN"),
                dev.ucplab.core.merchant.ContinueUrl.parse(LINK).orElseThrow());

        assertThat(linked(india).canonicalJson()).contains(
                "\"sellerSite\":\"www.shop-a.example\",\"shipsTo\":\"IN\",\"url\":");
        assertThat(linked(india).hash()).isEqualTo("4905a3b029c45fd183f24e87d9a66a3e6a527454e7f17ad86b4d8fc42e6f6227");
        assertThat(linked(new LinkSnapshot(india.sellerName(), india.sellerDomain(), india.sellerSite(),
                Optional.of("US"), india.url())).hash())
                .isEqualTo("8f6d4863716ba7e6b7bba71a13dbf46aace89612a00a0d4f0b35a4fbd3561eb2");
        assertThat(linked(SHOP_A).hash()).isEqualTo("25d64f9a2fb0bdbc7c37f11ca53605859486f3b57080ea0614abb6653dc88186");
    }

    // Covers: I2, I3, 26.7 (the hash changes with any part of the link: an approval of one link cannot hand off another)
    @Test
    void theHash_changesWithAnyPartOfTheLink() {
        List<ApprovalSnapshot> variants = List.of(
                linked(link("Example Shop B", "shop-a.myshopify.example", Optional.of("www.shop-a.example"), LINK)),
                linked(link("Example Shop A", "shop-b.myshopify.example", Optional.of("www.shop-a.example"), LINK)),
                linked(link("Example Shop A", "shop-a.myshopify.example", Optional.of("www.shop-b.example"), LINK)),
                linked(link("Example Shop A", "shop-a.myshopify.example", Optional.empty(), LINK)),
                linked(link("Example Shop A", "shop-a.myshopify.example", Optional.of("www.shop-a.example"),
                        LINK.replace("FAKETAG", "OTHERTAG"))));

        assertThat(variants).extracting(ApprovalSnapshot::hash).doesNotContain(linked(SHOP_A).hash())
                .doesNotHaveDuplicates();
    }

    // Covers: I8, 26.7 (the snapshot's text shows the link by its host only)
    @Test
    void theSnapshotsText_showsTheLinkByItsHostOnly() {
        assertThat(linked(SHOP_A).toString()).doesNotContain("_gsid").doesNotContain("FAKETAG")
                .doesNotContain("/buy/");
    }
}
